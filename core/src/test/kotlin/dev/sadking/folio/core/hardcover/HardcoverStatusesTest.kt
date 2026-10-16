package dev.sadking.folio.core.hardcover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardcoverStatusesTest {
    private val pairs =
        listOf(
            1 to "Want to Read",
            2 to "Reading",
            3 to "Finished",
            4 to "Paused",
            5 to "Abandoned",
        )

    @Test
    fun phoneWordsMapToHardcoverStatusesBothWays() {
        for ((status, word) in pairs) {
            assertEquals(status, HardcoverStatuses.fromPhone(word))
            assertEquals(word, HardcoverStatuses.toPhone(status))
        }
        assertEquals(HardcoverStatuses.WANT_TO_READ, 1)
        assertEquals(HardcoverStatuses.CURRENTLY_READING, 2)
        assertEquals(HardcoverStatuses.READ, 3)
        assertEquals(HardcoverStatuses.PAUSED, 4)
        assertEquals(HardcoverStatuses.DID_NOT_FINISH, 5)
        assertEquals(HardcoverStatuses.NONE, 0)
    }

    @Test
    fun fromPhoneIgnoresCaseAndSpaces() {
        assertEquals(3, HardcoverStatuses.fromPhone("  finished "))
        assertEquals(1, HardcoverStatuses.fromPhone("WANT TO READ"))
    }

    @Test
    fun unreadAndUnknownWordsHaveNoHardcoverStatus() {
        assertNull(HardcoverStatuses.fromPhone("Unread"))
        assertNull(HardcoverStatuses.fromPhone(""))
        assertNull(HardcoverStatuses.fromPhone("   "))
        assertNull(HardcoverStatuses.fromPhone("Currently Reading"))
        assertNull(HardcoverStatuses.fromPhone("Read"))
    }

    @Test
    fun unknownHardcoverStatusesHaveNoPhoneWord() {
        assertNull(HardcoverStatuses.toPhone(0))
        assertNull(HardcoverStatuses.toPhone(6))
        assertNull(HardcoverStatuses.toPhone(-1))
    }

    @Test
    fun catalogueEntriesWithNoStatusAreWantToRead() {
        assertEquals("Want to Read", HardcoverStatuses.catalogueWord(""))
        assertEquals("Want to Read", HardcoverStatuses.catalogueWord("  "))
        assertEquals("Paused", HardcoverStatuses.catalogueWord("Paused"))
        assertEquals("Unread", HardcoverStatuses.catalogueWord("Unread"))
    }
}
