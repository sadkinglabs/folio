package dev.sadking.folio.core

import dev.sadking.folio.core.ScanAction.Add
import dev.sadking.folio.core.ScanAction.Missing
import dev.sadking.folio.core.ScanAction.Relink
import dev.sadking.folio.core.ScanAction.Revise
import org.junit.Assert.*
import org.junit.Test

class FolderScanPlanTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val c = "c".repeat(64)
    private val d = "d".repeat(64)

    private fun book(
        id: String,
        path: String?,
        hash: String,
        present: Boolean = path != null,
        original: String = hash,
        removed: Boolean = false,
    ) = ScanBook(id, path, present, hash, original, removed)

    private val none = emptyList<ScanAction>()

    @Test
    fun anUnchangedFolderNeedsNothing() {
        assertEquals(
            none,
            FolderScanPlan.plan(
                listOf(FolderEntry("Ann Lee/Home.epub", a)),
                listOf(book("1", "Ann Lee/Home.epub", a)),
            ),
        )
    }

    @Test
    fun aRenamedFileIsFollowed() {
        // The recorded path is gone from the folder, so the scan passes the book as not found.
        assertEquals(
            listOf(Relink("1", "Ann Lee/Renamed.epub")),
            FolderScanPlan.plan(
                listOf(FolderEntry("Ann Lee/Renamed.epub", a)),
                listOf(book("1", null, a, present = false)),
            ),
        )
    }

    @Test
    fun newContentAtABooksOwnPathIsANewRevision() {
        assertEquals(
            listOf(Revise("1", "Ann Lee/Home.epub", b)),
            FolderScanPlan.plan(
                listOf(FolderEntry("Ann Lee/Home.epub", b)),
                listOf(book("1", "Ann Lee/Home.epub", a)),
            ),
        )
    }

    @Test
    fun aFileGoneEverywhereIsMissingNotDeleted() {
        assertEquals(
            listOf(Missing("1")),
            FolderScanPlan.plan(emptyList(), listOf(book("1", null, a, present = false))),
        )
    }

    @Test
    fun aBookOutsideTheFolderWithItsFileIsLeftAloneAndItsCopyIsNotAdded() {
        assertEquals(
            none,
            FolderScanPlan.plan(
                listOf(FolderEntry("Copy.epub", a)),
                listOf(book("1", null, a, present = true)),
            ),
        )
    }

    @Test
    fun unknownContentIsAddedOnceWhereItIs() {
        assertEquals(
            listOf(Add("A/One.epub", c), Add("A/Other.pdf", d)),
            FolderScanPlan.plan(
                listOf(
                    FolderEntry("B/Two.epub", c),
                    FolderEntry("A/One.epub", c),
                    FolderEntry("A/Other.pdf", d),
                ),
                emptyList(),
            ),
        )
    }

    @Test
    fun contentAlreadyInTheLibraryIsNeverAddedAgain() {
        // A copy of a book, and a book's original download.
        assertEquals(
            none,
            FolderScanPlan.plan(
                listOf(
                    FolderEntry("Ann Lee/Home.epub", a),
                    FolderEntry("Copy.epub", a),
                    FolderEntry("Old.epub", b),
                ),
                listOf(book("1", "Ann Lee/Home.epub", a, original = b)),
            ),
        )
    }

    @Test
    fun filesOfRecentlyRemovedBooksAreIgnored() {
        assertEquals(
            none,
            FolderScanPlan.plan(
                listOf(FolderEntry("Ann Lee/Gone.epub", b), FolderEntry("Moved.epub", a)),
                listOf(
                    book("1", "Ann Lee/Gone.epub", a, removed = true),
                    book("2", null, c, present = false, removed = true),
                ),
            ),
        )
    }

    @Test
    fun aBookMovedAwayFreesItsOldPathForNewContent() {
        assertEquals(
            listOf(Relink("1", "Ann Lee/Home (old).epub"), Add("Ann Lee/Home.epub", b)),
            FolderScanPlan.plan(
                listOf(
                    FolderEntry("Ann Lee/Home.epub", b),
                    FolderEntry("Ann Lee/Home (old).epub", a),
                ),
                listOf(book("1", "Ann Lee/Home.epub", a)),
            ),
        )
    }

    @Test
    fun anotherBooksContentAtABooksPathIsNotARevision() {
        assertEquals(
            listOf(Missing("1")),
            FolderScanPlan.plan(
                listOf(FolderEntry("Ann Lee/Home.epub", b), FolderEntry("Ann Lee/Away.epub", b)),
                listOf(book("1", "Ann Lee/Home.epub", a), book("2", "Ann Lee/Away.epub", b)),
            ),
        )
    }

    @Test
    fun aMissingBookIsLinkedBackWhenItsContentReturns() {
        // A book in private storage whose file is gone, found again in the folder.
        assertEquals(
            listOf(Relink("1", "Found/Home.epub")),
            FolderScanPlan.plan(
                listOf(FolderEntry("Found/Home.epub", a)),
                listOf(book("1", null, a, present = false)),
            ),
        )
    }

    @Test
    fun malformedInputIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            FolderScanPlan.plan(listOf(FolderEntry("../x.epub", a)), emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FolderScanPlan.plan(
                listOf(FolderEntry("x.epub", a), FolderEntry("x.epub", b)),
                emptyList(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            FolderScanPlan.plan(listOf(FolderEntry("x.epub", "not a hash")), emptyList())
        }
    }
}
