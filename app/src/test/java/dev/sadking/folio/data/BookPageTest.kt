package dev.sadking.folio.data

import dev.sadking.folio.ui.ReadStart
import dev.sadking.folio.ui.readStarts
import org.junit.Assert.*
import org.junit.Test

class BookPageTest {
    private fun phone(percent: Double?, at: Long = 10) =
        PhoneReading("book", "hash", "{}", "/body", percent, at)

    private fun snapshot(percent: Double?, at: Long) =
        ReadingSnapshot("book", "device", percent, "pos", "reading", 0, at, "sdr", "h", "hash")

    private fun report(device: String, percent: Double, seconds: Long) =
        KosyncReport("doc", "id-$device", device, "p", percent, seconds, seconds * 1000)

    @Test
    fun primaryIsContinueOnlyWithASavedPlace() {
        assertEquals(BookPrimary.FIND_EPUB, bookPrimary(false, phone(0.5)))
        assertEquals(BookPrimary.READ, bookPrimary(true, null))
        assertEquals(BookPrimary.READ, bookPrimary(true, phone(0.0)))
        assertEquals(BookPrimary.READ, bookPrimary(true, phone(null)))
        assertEquals(BookPrimary.CONTINUE, bookPrimary(true, phone(0.37)))
    }

    @Test
    fun readMenuOffersEreaderPlacesThenStartingOverThenListening() {
        val kindle = report("Kindle", 0.6, 20)
        assertEquals(
            listOf(ReadStart.Device(kindle), ReadStart.Beginning, ReadStart.Listen),
            readStarts(phoneHasPlace = true, listOf(kindle)),
        )
        // Without a place of its own, Read already starts at the beginning.
        assertEquals(listOf(ReadStart.Listen), readStarts(phoneHasPlace = false, emptyList()))
    }

    @Test
    fun pdfsAndComicsOfferOnlyStartingOver() {
        val kindle = report("Kindle", 0.6, 20)
        assertEquals(
            listOf(ReadStart.Beginning),
            readStarts(phoneHasPlace = true, listOf(kindle), epub = false),
        )
        assertEquals(
            emptyList<ReadStart>(),
            readStarts(phoneHasPlace = false, listOf(kindle), epub = false),
        )
    }

    @Test
    fun recordLosesShareAndGainsAttach() {
        val record = bookPageActions(false)
        assertEquals(BookPageAction.ATTACH, record.first())
        assertFalse(BookPageAction.SHARE in record)
        assertTrue(BookPageAction.REMOVE in record)
        val epub = bookPageActions(true)
        assertEquals(
            listOf(
                BookPageAction.EDIT,
                BookPageAction.PROGRESS,
                BookPageAction.HARDCOVER,
                BookPageAction.SHARE,
                BookPageAction.REMOVE,
            ),
            epub,
        )
    }

    @Test
    fun overflowNeedsTheEpubAndOffersDetachAndNotesOnlyWhenTheyApply() {
        assertTrue(bookOverflow(false, attached = true, hasNotes = true).isEmpty())
        assertEquals(
            listOf(BookOverflow.EXTERNAL_READER, BookOverflow.SAVE_COPY),
            bookOverflow(true, attached = false, hasNotes = false),
        )
        assertEquals(
            listOf(
                BookOverflow.EXTERNAL_READER,
                BookOverflow.SAVE_COPY,
                BookOverflow.DETACH,
                BookOverflow.EXPORT_NOTES,
            ),
            bookOverflow(true, attached = true, hasNotes = true),
        )
    }

    @Test
    fun quickActionsReadABookAndAttachToARecord() {
        val record = quickBookActions(false)
        assertTrue(BookAction.ATTACH in record)
        assertTrue(BookAction.REMOVE in record)
        assertFalse(BookAction.OPEN_READER in record)
        assertEquals(BookAction.OPEN_READER, quickBookActions(true).first())
        assertEquals(5, quickBookActions(true).size)
    }

    @Test
    fun entriesKeepEverySourceApartPhoneFirstEreadersNewestFirst() {
        val entries =
            progressEntries(
                hasEpub = true,
                phone = phone(0.37, at = 5_000),
                device = snapshot(0.41, at = 7_000),
                reports = listOf(report("Kindle", 0.5, 9), report("Kobo", 0.2, 3)),
                hardcoverLinked = true,
                hardcoverPage = 112,
            )
        assertEquals(
            listOf(
                ProgressSource.PHONE,
                ProgressSource.EREADER,
                ProgressSource.EREADER,
                ProgressSource.EREADER,
                ProgressSource.HARDCOVER,
            ),
            entries.map { it.source },
        )
        assertEquals(listOf(null, "Kindle", null, "Kobo"), entries.take(4).map { it.device })
        assertEquals(112, entries.last().page)
        assertEquals(0.37, entries.first().percent!!, 0.0)
    }

    @Test
    fun recordHasNoPhoneRowAndUnlinkedBookHasNoHardcoverRow() {
        assertTrue(progressEntries(false, phone(0.5), null, emptyList(), false).isEmpty())
        assertEquals(
            listOf(ProgressSource.HARDCOVER),
            progressEntries(false, phone(0.5), null, emptyList(), true).map { it.source },
        )
    }

    @Test
    fun outOfRangePercentagesAreShownAsUnknownNotClamped() {
        val entries = progressEntries(true, phone(1.5), snapshot(Double.NaN, 1), emptyList(), false)
        assertNull(entries[0].percent)
        assertNull(entries[1].percent)
    }

    @Test
    fun theEreaderFurthestAheadIsOffered() {
        val entries =
            progressEntries(
                true,
                phone(0.37),
                snapshot(0.372, 1),
                listOf(report("Kindle", 0.41, 2), report("Kobo", 0.6, 3)),
                false,
            )
        assertEquals("Kobo", aheadOfPhone(entries)?.device)
        assertNull(
            aheadOfPhone(progressEntries(true, phone(0.5), snapshot(0.4, 1), emptyList(), false))
        )
        // Without a phone place, any e-reader position counts as ahead.
        assertEquals(
            0.41,
            aheadOfPhone(
                    listOf(report("Kindle", 0.41, 2)).let {
                        progressEntries(true, null, null, it, false)
                    }
                )
                ?.percent!!,
            0.0,
        )
    }

    @Test
    fun averageColourSkipsTransparentPixelsAndStaysOpaque() {
        val red = 0xFFFF0000.toInt()
        val blue = 0xFF0000FF.toInt()
        val clear = 0x0000FF00
        val average = averageArgb(intArrayOf(red, blue, clear, clear))
        assertEquals(0xFF, average ushr 24)
        assertEquals(0x7F, (average shr 16) and 0xFF)
        assertEquals(0x00, (average shr 8) and 0xFF)
        assertEquals(0x7F, average and 0xFF)
        assertEquals(0xFF808080.toInt(), averageArgb(intArrayOf(clear)))
        assertEquals(0xFF808080.toInt(), averageArgb(IntArray(0)))
    }
}
