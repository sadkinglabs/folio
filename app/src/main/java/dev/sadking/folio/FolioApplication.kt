package dev.sadking.folio

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import dev.sadking.folio.data.*
import dev.sadking.folio.device.DeviceSettings
import dev.sadking.folio.sync.HardcoverSync
import dev.sadking.folio.sync.SyncState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FolioApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, LibraryDatabase::class.java, "folio.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }
    val repository by lazy { LibraryRepository(this, database) }
    /** One scan of the Books folder for the whole app, so runs never overlap (M9 step 2). */
    val folderScan by lazy {
        BooksFolderScan(
            repository,
            java.io.File(filesDir, BooksFolderScan.CACHE_NAME),
            FolderScanNotes.preferences(this),
            canRead = { BooksFolder(this).hasAccess },
        )
    }
    val deviceSettings by lazy { DeviceSettings(this) }
    val hardcoverOutbox by lazy { dev.sadking.folio.sync.HardcoverOutbox(this) }
    /** App-wide work that must outlive a screen (statistics writes and indexing). */
    val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val link by lazy { dev.sadking.folio.link.FolioLink(this, database.library()) }
    val operations by lazy {
        OperationRunner(
            authentication = { deviceSettings.load().authentication },
            text = { resources.failureText() },
        )
    }
    /** The owner's reading goals (M12). */
    val statsGoals by lazy { StatsGoals(getSharedPreferences(StatsGoals.PREFS, MODE_PRIVATE)) }
    /** The reading statistics (M12): one repository for the whole app. */
    val stats by lazy {
        val sync = SyncState.of(this)
        StatsRepository(
            database.stats(),
            database.library(),
            roster =
                deviceRosterFlow(sync.ereaders, database.library()) { link.settings.phoneDeviceId },
            ereaders = sync.ereaders.map { it.values.toList() },
            goals = statsGoals,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this leaves a report the owner can send.
        CrashReports.install(this)
        // Sync with Hardcover (M10): the periodic pull and the watcher that matches new books.
        HardcoverSync.get(this).start()
        // Word counts and KOReader digests for the statistics (M12): after each change to the
        // library.
        background.launch {
            database.library().books().debounce(2_000).collect {
                StatsIndexer(database.library(), database.stats()).run()
            }
        }
        // Folio Link runs while Librillo is open and for 30 minutes after (M9). The process
        // lifecycle covers every activity, the reader included, and ignores rotation.
        ProcessLifecycleOwner.get()
            .lifecycle
            .addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        link.onAppForeground()
                        HardcoverSync.get(this@FolioApplication).onAppOpen()
                    }

                    override fun onStop(owner: LifecycleOwner) = link.onAppBackground()
                }
            )
    }
}
