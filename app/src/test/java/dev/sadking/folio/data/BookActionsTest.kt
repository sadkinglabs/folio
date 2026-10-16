package dev.sadking.folio.data

import org.junit.Assert.*
import org.junit.Test

class BookActionsTest {
    private fun book(id: String = "file") =
        Book(
            id,
            "Owned",
            "[]",
            originalPath = "original",
            currentPath = "revision",
            originalHash = "a",
            currentHash = "b",
        )

    private fun wish(id: String = "wish", linked: String? = null) =
        WishBook(id, "hardcover:1", "Catalogue", "[]", linkedBookId = linked)

    @Test
    fun epubActionsIncludeActualManagementAndOptionalExport() {
        val actions = availableBookActions(true, true)
        assertTrue(
            actions.containsAll(
                listOf(
                    BookAction.OPEN_READER,
                    BookAction.PROGRESS,
                    BookAction.EDIT,
                    BookAction.LISTS,
                    BookAction.STATUS,
                    BookAction.EXPORT_NOTES,
                    BookAction.REMOVE,
                )
            )
        )
        assertFalse(BookAction.EXPORT_NOTES in availableBookActions(true, false))
        assertFalse(BookAction.ATTACH in actions)
    }

    @Test
    fun catalogueActionsNeverOfferTransferOrEpubEditing() {
        val actions = availableBookActions(false, true)
        assertTrue(
            actions.containsAll(
                listOf(
                    BookAction.SELECT,
                    BookAction.ATTACH,
                    BookAction.SEARCH,
                    BookAction.LISTS,
                    BookAction.PROGRESS,
                    BookAction.REMOVE,
                )
            )
        )
        assertFalse(
            actions.any {
                it in
                    listOf(
                        BookAction.OPEN_READER,
                        BookAction.EXPORT_EPUB,
                        BookAction.EXPORT_NOTES,
                    )
            }
        )
    }

    @Test
    fun removingAttachedEditionAlsoRemovesItsCatalogueEntry() {
        val plan = localRemovalPlan(setOf("file"), listOf(book()), listOf(wish(linked = "file")))
        assertEquals(setOf("file"), plan.bookIds)
        assertEquals(setOf("wish"), plan.wishIds)
        assertEquals(1, plan.visibleCount)
    }

    @Test
    fun mixedSelectionKeepsUnselectedEditionsAndCountsBooksOnce() {
        val plan =
            localRemovalPlan(
                setOf("file", "wish", "unowned"),
                listOf(book(), book("other")),
                listOf(wish(linked = "file"), wish("unowned"), wish("retained", "other")),
            )
        assertEquals(setOf("file"), plan.bookIds)
        assertEquals(setOf("wish", "unowned"), plan.wishIds)
        assertEquals(2, plan.visibleCount)
    }

    @Test
    fun catalogueOnlyRemovalCannotClaimUnrelatedFile() {
        val plan = localRemovalPlan(setOf("wish"), listOf(book()), listOf(wish()))
        assertTrue(plan.bookIds.isEmpty())
        assertEquals(setOf("wish"), plan.wishIds)
    }

    @Test
    fun retryAfterRemovalIsHarmless() {
        assertEquals(
            LocalRemovalPlan(emptySet(), emptySet(), 0),
            localRemovalPlan(setOf("gone"), emptyList(), emptyList()),
        )
    }
}
