package dev.sadking.folio

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import dev.sadking.folio.data.*
import dev.sadking.folio.device.DeviceSettings
import kotlinx.coroutines.flow.first

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
    val syncCoordinator by lazy { dev.sadking.folio.sync.SyncCoordinator(this) }
    val deviceSettings by lazy { DeviceSettings(this) }
    val linkQueue by lazy { dev.sadking.folio.link.LinkQueue(this) }
    val hardcoverOutbox by lazy { dev.sadking.folio.sync.HardcoverOutbox(this) }
    val link by lazy { dev.sadking.folio.link.FolioLink(this, database.library()) }
    val operations by lazy {
        OperationRunner(
            transferActive = { database.library().jobs().first().any { it.active } },
            authentication = { deviceSettings.load().authentication },
            text = { resources.failureText() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Folio Link runs while Librillo is open and for 30 minutes after (M9). The process
        // lifecycle covers every activity, the reader included, and ignores rotation.
        ProcessLifecycleOwner.get()
            .lifecycle
            .addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = link.onAppForeground()

                    override fun onStop(owner: LifecycleOwner) = link.onAppBackground()
                }
            )
    }
}
