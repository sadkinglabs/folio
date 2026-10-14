package dev.sadking.folio

import android.app.Application
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
    val syncCoordinator by lazy { dev.sadking.folio.sync.SyncCoordinator(this) }
    val deviceSettings by lazy { DeviceSettings(this) }
    val hardcoverOutbox by lazy { dev.sadking.folio.sync.HardcoverOutbox(this) }
    val link by lazy { dev.sadking.folio.link.FolioLink(this, database.library()) }
    val operations by lazy {
        OperationRunner(
            transferActive = { database.library().jobs().first().any { it.active } },
            authentication = { deviceSettings.load().authentication },
            text = { resources.failureText() },
        )
    }
}
