package dev.sadking.folio

import android.app.Application
import androidx.room.Room
import dev.sadking.folio.data.*
import dev.sadking.folio.device.DeviceSettings

class FolioApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, LibraryDatabase::class.java, "folio.db")
            .addMigrations(*ALL_MIGRATIONS)
            .build()
    }
    val repository by lazy { LibraryRepository(this, database) }
    val syncCoordinator by lazy { dev.sadking.folio.sync.SyncCoordinator(this) }
    val deviceSettings by lazy { DeviceSettings(this) }
}
