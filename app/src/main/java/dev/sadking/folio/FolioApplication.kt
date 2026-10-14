package dev.sadking.folio

import android.app.Application
import androidx.room.Room
import dev.sadking.folio.data.*
import dev.sadking.folio.device.DeviceSettings

class FolioApplication : Application() {
    val database by lazy {
        Room.databaseBuilder(this, LibraryDatabase::class.java, "folio.db")
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            )
            .build()
    }
    val repository by lazy { LibraryRepository(this, database) }
    val syncCoordinator by lazy { dev.sadking.folio.sync.SyncCoordinator(this) }
    val deviceSettings by lazy { DeviceSettings(this) }
}
