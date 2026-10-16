package dev.sadking.folio.link

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.sadking.folio.FolioApplication
import dev.sadking.folio.data.KosyncReport
import dev.sadking.folio.data.LibraryDao
import dev.sadking.folio.sync.RoomSyncLibrary
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Keeps every device's kosync reports in Room. Called from the server's own threads. */
class RoomKosyncStore(
    private val dao: LibraryDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val onSaved: (KosyncProgress) -> Unit = {},
) : KosyncStore {
    override fun save(user: String, progress: KosyncProgress) {
        dao.saveKosyncReport(
            KosyncReport(
                progress.document,
                progress.deviceId.ifEmpty { progress.device },
                progress.device,
                progress.progress,
                progress.percentage,
                progress.timestamp,
                now(),
            )
        )
        onSaved(progress)
    }

    override fun latest(user: String, document: String) =
        dao.latestKosyncReport(document)?.let {
            KosyncProgress(
                it.document,
                it.progress,
                it.percentage,
                it.device,
                it.deviceId,
                it.timestamp,
            )
        }
}

/** Folio Link as the rest of the app sees it: settings, the log, and whether it is running. */
class FolioLink(context: Context, dao: LibraryDao) {
    private val app = context.applicationContext
    val settings = LinkSettings(app)
    val log = LinkLog(java.io.File(app.filesDir, "folio-link-log.json"))
    // An e-reader's new position also goes to Hardcover when automatic updates are on.
    val kosync =
        Kosync(
            RoomKosyncStore(dao) {
                (app as FolioApplication).hardcoverOutbox.queueDocument(it.document, it.percentage)
            },
            { settings.account() },
        )

    /** The phone's side of two-way sync with the e-reader's plugin (protocol v2). */
    val sync = RoomSyncLibrary(app as FolioApplication)

    /** Progress sync, the OPDS catalogue, the plugin's queue and sync, behind one login. */
    val routes =
        LinkRoutes(
            kosync,
            RoomLinkLibrary(dao),
            { settings.account() },
            {
                LinkLabels(
                    app.getString(dev.sadking.folio.R.string.link_catalogue),
                    app.getString(dev.sadking.folio.R.string.link_catalogue_recent),
                    app.getString(dev.sadking.folio.R.string.link_catalogue_lists),
                )
            },
            sync = sync,
        )

    /** When Link stops by itself: 30 minutes after Librillo leaves the foreground. */
    val lifecycle = LinkLifecycle(SystemClock::elapsedRealtime)

    internal val _port = MutableStateFlow<Int?>(null)
    /** The port Link is listening on, or null when it is off. */
    val port: StateFlow<Int?> = _port

    /** The owner turned Link on: it is set up if it was not, syncs automatically, and starts. */
    fun start() {
        settings.password()
        settings.enabled = true
        startService()
    }

    /** The owner turned Link off: it stops and no longer starts by itself. */
    fun stop() {
        settings.enabled = false
        app.stopService(Intent(app, FolioLinkService::class.java))
    }

    /**
     * The Sync button: the phone starts listening now, even with "Sync automatically" off, and
     * stops by itself as usual ([LinkLifecycle]). The setting is left as it is.
     */
    fun syncNow() {
        settings.password()
        if (lifecycle.inForeground) runCatching { startService() }
    }

    /** Librillo came to the foreground: Link starts if it is set up and syncs automatically. */
    fun onAppForeground() {
        lifecycle.foreground()
        startIfAllowed()
    }

    /** Librillo left the foreground: Link stops by itself 30 minutes later. */
    fun onAppBackground() = lifecycle.background()

    /**
     * Starts Link if it is set up, syncs automatically and Librillo is in the foreground, which
     * Android requires to start a foreground service. Starting it again does no harm.
     */
    fun startIfAllowed() {
        if (settings.enabled && lifecycle.inForeground) runCatching { startService() }
    }

    private fun startService() {
        ContextCompat.startForegroundService(app, Intent(app, FolioLinkService::class.java))
    }

    companion object {
        /**
         * The phone's local IPv4 addresses, Wi-Fi and hotspot included. Enumerating interfaces
         * finds the hotspot address too, which the connectivity APIs do not report.
         */
        fun addresses(): List<String> =
            runCatching {
                    NetworkInterface.getNetworkInterfaces()
                        .toList()
                        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                        .flatMap { it.inetAddresses.toList() }
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress }
                        .mapNotNull { it.hostAddress }
                        .distinct()
                }
                .getOrDefault(emptyList())
    }
}
