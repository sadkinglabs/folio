package dev.sadking.folio.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.MainActivity
import dev.sadking.folio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps Folio Link listening while the phone is in a pocket. A `connectedDevice` foreground
 * service, because the e-reader is a nearby device Folio exchanges data with; `dataSync` would be
 * capped at six hours a day. A Wi-Fi lock keeps incoming connections able to wake the radio.
 *
 * It runs while Librillo is open and stops itself 30 minutes after Librillo leaves the foreground,
 * later when an e-reader is in the middle of a sync ([LinkLifecycle]). Librillo starts it again
 * when it comes back, so it is not sticky.
 */
class FolioLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val link
        get() = (application as FolioApplication).link

    private var server: FolioLinkServer? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discovery: LinkDiscoveryResponder? = null
    private var watcher: Job? = null
    private var lastStartId = 0

    private val screen =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val kind =
                    when (intent.action) {
                        Intent.ACTION_SCREEN_OFF -> LinkLog.Kind.SCREEN_OFF
                        Intent.ACTION_SCREEN_ON -> LinkLog.Kind.SCREEN_ON
                        else -> return
                    }
                link.log.add(LinkLog.Entry(System.currentTimeMillis(), kind))
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                    CHANNEL,
                    getString(R.string.link_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply { setShowBadge(false) }
        )
        registerReceiver(
            screen,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's "Turn off": no more automatic sync until the owner turns it on.
            link.settings.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }
        lastStartId = startId
        link.lifecycle.serviceStarted()
        val port = link.settings.port
        ServiceCompat.startForeground(
            this,
            NOTIFICATION,
            notification(port),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        holdLocks()
        if (server == null) {
            val created =
                FolioLinkServer(link.routes) {
                    link.log.request(it)
                    link.lifecycle.request(it.route, it.status, it.remote)
                }
            server = created
            scope.launch {
                try {
                    val bound = created.start(port)
                    link._port.value = bound
                    // Lets the Folio plugin find this phone; Link works without it.
                    discovery =
                        LinkDiscoveryResponder({ link._port.value }, { link.settings.account() })
                            .also { runCatching { it.start() } }
                    link.log.add(
                        LinkLog.Entry(
                            System.currentTimeMillis(),
                            LinkLog.Kind.STARTED,
                            port = bound,
                        )
                    )
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    link.log.add(
                        LinkLog.Entry(
                            System.currentTimeMillis(),
                            LinkLog.Kind.FAILED_TO_START,
                            port = port,
                        )
                    )
                    server = null
                    stopSelf()
                }
            }
        }
        if (watcher == null) watcher = scope.launch(Dispatchers.Main) { stopWhenIdle() }
        return START_NOT_STICKY
    }

    /**
     * Stops the service once [LinkLifecycle] says so. On the main thread, like onStartCommand, and
     * only for the latest start, so a start that has just come in is never undone.
     */
    private suspend fun stopWhenIdle() {
        while (true) {
            delay(LinkLifecycle.CHECK_MS)
            // False when a newer start is on its way: keep watching.
            if (link.lifecycle.shouldStop() && stopSelfResult(lastStartId)) return
        }
    }

    private fun holdLocks() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        // Some phones drop broadcast packets unless a multicast lock is held.
        if (multicastLock == null)
            multicastLock =
                wifi.createMulticastLock("folio:link").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        if (wifiLock == null) {
            wifiLock =
                wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "folio:link").apply {
                    acquire()
                }
        }
        if (link.settings.keepAwake && wakeLock == null)
            wakeLock =
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "folio:link")
                    .apply { acquire() }
    }

    private fun notification(port: Int): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, FolioLinkService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val address = FolioLink.addresses().firstOrNull()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_folio)
            .setContentTitle(getString(R.string.link_notification_title))
            .setContentText(
                if (address != null) getString(R.string.link_notification_text, "$address:$port")
                else getString(R.string.link_notification_no_network)
            )
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.link_stop), stop)
            .build()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screen) }
        discovery?.stop()
        discovery = null
        server?.stop()
        server = null
        if (link._port.value != null)
            link.log.add(LinkLog.Entry(System.currentTimeMillis(), LinkLog.Kind.STOPPED))
        link._port.value = null
        wifiLock?.takeIf { it.isHeld }?.release()
        multicastLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "folio-link"
        private const val NOTIFICATION = 7301
        private const val ACTION_STOP = "dev.sadking.folio.link.STOP"
    }
}
