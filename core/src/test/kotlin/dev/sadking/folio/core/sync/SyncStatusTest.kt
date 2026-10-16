package dev.sadking.folio.core.sync

import org.junit.Assert.*
import org.junit.Test

class SyncStatusTest {
    @Test
    fun phoneStatusesMapToTheKindlesWords() {
        val expected =
            mapOf(
                "Unread" to "unread",
                "Want to Read" to "unread",
                "" to "unread",
                "Reading" to "reading",
                "Finished" to "finished",
                "Paused" to "abandoned",
                "Abandoned" to "abandoned",
            )
        expected.forEach { (phone, kindle) ->
            assertEquals(phone, kindle, SyncStatus.fromPhone(phone))
        }
        assertNull(SyncStatus.fromPhone("Did not finish"))
    }

    @Test
    fun kindleStatusesAreReadInAnyCaseAndKoreadersOwnWords() {
        assertEquals("finished", SyncStatus.normalize("complete"))
        assertEquals("abandoned", SyncStatus.normalize("On hold"))
        assertEquals("reading", SyncStatus.normalize(" READING "))
        assertNull(SyncStatus.normalize(null))
        assertNull(SyncStatus.normalize(""))
    }

    @Test
    fun eachKindleStatusHasAPhoneName() {
        assertEquals(
            listOf("Unread", "Reading", "Finished", "Paused"),
            SyncStatus.ALL.map(SyncStatus::phoneName),
        )
        SyncStatus.ALL.forEach { assertEquals(it, SyncStatus.fromPhone(SyncStatus.phoneName(it))) }
        assertThrows(IllegalArgumentException::class.java) { SyncStatus.phoneName("lost") }
    }
}
