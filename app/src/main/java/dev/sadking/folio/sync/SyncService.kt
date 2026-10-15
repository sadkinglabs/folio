package dev.sadking.folio.sync

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.content.ContextCompat
import dev.sadking.folio.*
import dev.sadking.folio.data.SyncJob
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    private val alive = AtomicBoolean(true)
    private val coordinator
        get() = (application as FolioApplication).syncCoordinator

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Kindle transfers", NotificationManager.IMPORTANCE_LOW)
            )
        val notification = notification(null)
        try {
            startForeground(
                NOTIFICATION,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: RuntimeException) {
            alive.set(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra("job")
        if (id == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!alive.get()) {
            coordinator.scope.launch { coordinator.startFailed(id) }
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (task?.isActive != true)
            task = scope.launch {
                try {
                    coordinator.execute(id) { job ->
                        if (alive.get())
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION, notification(job))
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.Main) { stopSelf(startId) }
                }
            }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        coordinator.disconnect()
        stopSelf(startId)
    }

    override fun onDestroy() {
        alive.set(false)
        scope.cancel()
        coordinator.disconnect()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun notification(job: SyncJob?): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val cancel =
            PendingIntent.getBroadcast(
                this,
                0,
                Intent(this, CancelSyncReceiver::class.java).putExtra("job", job?.id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_folio)
                .setContentTitle("Folio · Kindle sync")
                .setContentText(
                    if (job == null) "Starting transfer…"
                    else
                        "${job.completedBooks} of ${job.totalBooks} books verified · ${job.phase.lowercase()}"
                )
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (job != null && job.active)
            builder.addAction(Notification.Action.Builder(null, "Cancel", cancel).build())
        val progress =
            if (job != null && job.totalBytes > 0)
                (job.bytesSent * 100 / job.totalBytes).toInt().coerceIn(0, 100)
            else 0
        builder.setProgress(100, progress, job == null || job.phase == "CONNECTING")
        return builder.build()
    }

    companion object {
        private const val CHANNEL = "folio-sync"
        private const val NOTIFICATION = 704

        fun start(context: Context, id: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, SyncService::class.java).putExtra("job", id),
            )
        }
    }
}

class CancelSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("job") ?: return
        val pending = goAsync()
        val coordinator = (context.applicationContext as FolioApplication).syncCoordinator
        coordinator.scope.launch {
            try {
                coordinator.cancel(id)
            } finally {
                pending.finish()
            }
        }
    }
}
