package dev.sadking.folio.data

import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderScanNotesTest {
    private val prefs: SharedPreferences
        get() = FolderScanNotes.preferences(ApplicationProvider.getApplicationContext())

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun clearingOneBookKeepsTheOthers() {
        prefs.edit().putStringSet(FolderScanNotes.CHANGED_IDS, setOf("a", "b")).commit()
        FolderScanNotes.clearChanged(prefs, "a")
        assertEquals(setOf("b"), FolderScanNotes.changedIds(prefs))
        FolderScanNotes.clearChanged(prefs, "unknown")
        assertEquals(setOf("b"), FolderScanNotes.changedIds(prefs))
    }

    @Test
    fun lastScanIsNullUntilTheScanWritesIt() {
        assertNull(FolderScanNotes.lastScan(prefs))
        prefs.edit().putLong(FolderScanNotes.LAST_SCAN_AT, 1000L).commit()
        assertEquals(FolderScanNotes.LastScan(1000L, ""), FolderScanNotes.lastScan(prefs))
        prefs
            .edit()
            .putString(FolderScanNotes.LAST_SCAN_MESSAGE, "Added 1 book from the Inbox.")
            .commit()
        assertEquals("Added 1 book from the Inbox.", FolderScanNotes.lastScan(prefs)?.message)
    }
}
