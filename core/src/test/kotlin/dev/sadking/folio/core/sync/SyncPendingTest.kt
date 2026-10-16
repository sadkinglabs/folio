package dev.sadking.folio.core.sync

import dev.sadking.folio.core.sync.Fx.A
import dev.sadking.folio.core.sync.Fx.B
import dev.sadking.folio.core.sync.Fx.ann
import dev.sadking.folio.core.sync.Fx.base
import dev.sadking.folio.core.sync.Fx.book
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the phone tells a polling e-reader it has something new (`hello`'s `pending`). */
class SyncPendingTest {
    private val path = "Librillo/Ann Lee/Home.epub"

    /** A book both sides agreed on at the last sync, unread, with no notes or lists. */
    private val synced = book("b1", A)
    private val agreed = base("b1", path, A)

    private fun pending(
        phone: List<PhoneBook>,
        baselines: List<Baseline> = emptyList(),
        root: String? = null,
        waiting: Set<String> = emptySet(),
    ) = SyncPending.pending(phone, baselines, root, waiting)

    @Test
    fun nothingToSendIsNotPending() {
        assertFalse(pending(emptyList()))
        assertFalse(pending(listOf(synced), listOf(agreed)))
    }

    @Test
    fun aNewBookIsPending() {
        assertTrue(pending(listOf(synced)))
    }

    @Test
    fun aBookTheKindleCannotTakeIsNotPending() {
        assertFalse(pending(listOf(book("b1", A, hasFile = false))))
        assertFalse(pending(listOf(book("b1", A, format = "mobi"))))
        assertFalse(pending(listOf(book("b1", "not a hash"))))
        assertFalse(pending(listOf(book("b1", A, removed = true))))
    }

    @Test
    fun aMissingFileWithABaselineIsNotPending() {
        assertFalse(pending(listOf(book("b1", A, hasFile = false)), listOf(agreed)))
    }

    @Test
    fun aNewFileIsPending() {
        assertTrue(pending(listOf(book("b1", B)), listOf(agreed)))
    }

    @Test
    fun aNewTitleOrAuthorIsPending() {
        assertTrue(pending(listOf(book("b1", A, title = "Away")), listOf(agreed)))
        assertTrue(pending(listOf(book("b1", A, authors = listOf("Bo Ray"))), listOf(agreed)))
    }

    @Test
    fun aNumberedNameOrOtherCaseIsStillInPlace() {
        assertFalse(
            pending(listOf(synced), listOf(agreed.copy(path = "Librillo/Ann Lee/Home (2).epub")))
        )
        assertFalse(
            pending(listOf(synced), listOf(agreed.copy(path = "librillo/ann lee/HOME.epub")))
        )
        assertTrue(
            pending(listOf(synced), listOf(agreed.copy(path = "Librillo/Ann Lee/Home (0).epub")))
        )
    }

    @Test
    fun anyLibraryFolderCountsUnlessOneIsGiven() {
        val elsewhere = listOf(agreed.copy(path = "Books/Ann Lee/Home.epub"))
        assertFalse(pending(listOf(synced), elsewhere))
        assertTrue(pending(listOf(synced), elsewhere, root = "Librillo"))
        assertFalse(pending(listOf(synced), elsewhere, root = "/Books/"))
        // The book's own name decides, not a folder that merely ends the same way.
        assertTrue(
            pending(listOf(synced), listOf(agreed.copy(path = "Librillo/XAnn Lee/Home.epub")))
        )
    }

    @Test
    fun aNewStatusIsPending() {
        assertTrue(pending(listOf(book("b1", A, status = "Finished")), listOf(agreed)))
        assertTrue(
            pending(
                listOf(book("b1", A, status = "Paused")),
                listOf(agreed.copy(status = "reading")),
            )
        )
    }

    @Test
    fun theSameStatusInOtherWordsIsNotPending() {
        assertFalse(pending(listOf(book("b1", A, status = "Want to Read")), listOf(agreed)))
        assertFalse(
            pending(
                listOf(book("b1", A, status = "Abandoned")),
                listOf(agreed.copy(status = "abandoned")),
            )
        )
        // A status the e-reader has no word for is left alone by sync.
        assertFalse(pending(listOf(book("b1", A, status = "Did not finish")), listOf(agreed)))
    }

    @Test
    fun aStatusTheSidesDidNotAgreeOnIsNotPending() {
        assertFalse(
            pending(listOf(book("b1", A, status = "Finished")), listOf(agreed.copy(status = "")))
        )
    }

    @Test
    fun aNewAnnotationIsPending() {
        assertTrue(pending(listOf(book("b1", A, annotations = listOf(ann("n1")))), listOf(agreed)))
        assertFalse(
            pending(
                listOf(book("b1", A, annotations = listOf(ann("n1")))),
                listOf(agreed.copy(annotationIds = setOf("n1", "n2"))),
            )
        )
    }

    @Test
    fun aListAddedOrRemovedIsPending() {
        assertTrue(pending(listOf(book("b1", A, lists = listOf("Sundays"))), listOf(agreed)))
        assertTrue(pending(listOf(synced), listOf(agreed.copy(collections = setOf("Sundays")))))
        assertFalse(
            pending(
                listOf(book("b1", A, lists = listOf(" Sundays", "Sundays", ""))),
                listOf(agreed.copy(collections = setOf("Sundays"))),
            )
        )
    }

    @Test
    fun aBookRemovedOrPurgedOnThePhoneIsPending() {
        assertTrue(pending(listOf(book("b1", A, removed = true)), listOf(agreed)))
        assertTrue(pending(emptyList(), listOf(agreed)))
    }

    @Test
    fun booksWaitingOnAQuestionDoNotCount() {
        val waiting = setOf("b1")
        assertFalse(pending(listOf(book("b1", B)), listOf(agreed), waiting = waiting))
        assertFalse(pending(emptyList(), listOf(agreed), waiting = waiting))
        assertTrue(pending(listOf(book("b1", B), book("b2", A)), listOf(agreed), waiting = waiting))
    }
}
