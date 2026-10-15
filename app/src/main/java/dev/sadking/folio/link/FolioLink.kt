package dev.sadking.folio.link

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.sadking.folio.data.KosyncReport
import dev.sadking.folio.data.LibraryDao
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
                (app as dev.sadking.folio.FolioApplication)
                    .hardcoverOutbox
                    .queueDocument(it.document, it.percentage)
            },
            { settings.account() },
        )

    /** Progress sync, the OPDS catalogue and the plugin's queue, behind one login. */
    val routes =
        LinkRoutes(
            kosync,
            RoomLinkLibrary(dao, (app as dev.sadking.folio.FolioApplication).linkQueue),
            { settings.account() },
            {
                LinkLabels(
                    app.getString(dev.sadking.folio.R.string.link_catalogue),
                    app.getString(dev.sadking.folio.R.string.link_catalogue_recent),
                    app.getString(dev.sadking.folio.R.string.link_catalogue_lists),
                )
            },
        )

    internal val _port = MutableStateFlow<Int?>(null)
    /** The port Link is listening on, or null when it is off. */
    val port: StateFlow<Int?> = _port

    fun start() {
        settings.enabled = true
        ContextCompat.startForegroundService(app, Intent(app, FolioLinkService::class.java))
    }

    fun stop() {
        settings.enabled = false
        app.stopService(Intent(app, FolioLinkService::class.java))
    }

    /** Restarts Link after the app opens, if the user left it on. */
    fun resumeIfEnabled() {
        if (settings.enabled && _port.value == null) runCatching { start() }
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
