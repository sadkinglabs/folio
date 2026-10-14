package dev.sadking.folio.core

import org.junit.Assert.*
import org.junit.Test

class FolderReconciliationTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test
    fun additionsTravelBothWaysAndFoldersStayInPaths() {
        val plan =
            FolderReconciliation.plan(
                listOf(FolderFile("Fiction/Phone.epub", a)),
                listOf(FolderFile("Essays/Kindle.epub", b)),
            )
        assertEquals(listOf(FolderAction.TO_PHONE, FolderAction.TO_KINDLE), plan.map { it.action })
        assertEquals("Fiction/Phone.epub", plan[1].path)
    }

    @Test
    fun identicalBytesAreIdempotent() {
        assertEquals(
            FolderAction.VERIFIED,
            FolderReconciliation.plan(
                    listOf(FolderFile("Book.epub", a)),
                    listOf(FolderFile("Book.epub", a)),
                )
                .single()
                .action,
        )
    }

    @Test
    fun changedContentNeverOverwrites() {
        assertEquals(
            FolderAction.CONFLICT,
            FolderReconciliation.plan(
                    listOf(FolderFile("Book.epub", a)),
                    listOf(FolderFile("Book.epub", b)),
                )
                .single()
                .action,
        )
    }

    @Test
    fun deletionIsNeverInferred() {
        assertEquals(
            FolderAction.TO_PHONE,
            FolderReconciliation.plan(emptyList(), listOf(FolderFile("Book.epub", a)))
                .single()
                .action,
        )
    }

    @Test
    fun unsafeAndAmbiguousPathsFailClosed() {
        listOf(
                "../Book.epub",
                "/Book.epub",
                "A//Book.epub",
                ".hidden/Book.epub",
                "Book.sdr/notes.epub",
                "A\\Book.epub",
            )
            .forEach { path ->
                assertThrows(IllegalArgumentException::class.java) {
                    FolderReconciliation.plan(listOf(FolderFile(path, a)), emptyList())
                }
            }
        assertThrows(IllegalArgumentException::class.java) {
            FolderReconciliation.plan(
                listOf(FolderFile("Book.epub", a), FolderFile("Book.epub", b)),
                emptyList(),
            )
        }
    }
}
