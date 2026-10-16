package dev.sadking.folio.core

import org.junit.Assert.*
import org.junit.Test

class BookPathsTest {
    @Test
    fun firstAuthorFolderAndTitleFile() {
        assertEquals(
            "Ursula K. Le Guin/The Dispossessed.epub",
            BookPaths.path(listOf("Ursula K. Le Guin", "Someone Else"), "The Dispossessed"),
        )
    }

    @Test
    fun missingAuthorOrTitleGetsAPlainName() {
        assertEquals("Unknown author/Untitled.epub", BookPaths.path(emptyList(), ""))
        assertEquals("Unknown author/Notes.epub", BookPaths.path(listOf("  ", ""), "Notes"))
        assertEquals("Unknown author/Untitled.epub", BookPaths.path(listOf("..."), "?*"))
    }

    @Test
    fun charactersFatRefusesAreReplaced() {
        assertEquals(
            "AC DC/What Now Why Not.epub",
            BookPaths.path(listOf("AC/DC"), "What: Now? \"Why\" <Not>|"),
        )
        assertEquals("A B/Tab Line.epub", BookPaths.path(listOf("A\\B"), "Tab\tLine\n"))
    }

    @Test
    fun noHiddenNamesAndNoTrailingDots() {
        assertEquals("Anon/Wait.epub", BookPaths.path(listOf(".Anon"), "Wait..."))
        assertEquals("Unknown author/Untitled.epub", BookPaths.path(listOf(".."), "."))
    }

    @Test
    fun reservedWindowsNamesAreChanged() {
        assertEquals("CON _/aux _.epub", BookPaths.path(listOf("CON"), "aux"))
        assertEquals("Prince/Con Air.epub", BookPaths.path(listOf("Prince"), "Con Air"))
    }

    @Test
    fun clashesAreCaseInsensitive() {
        val taken = setOf("Ann Lee/Home.epub", "Ann Lee/Home (2).epub").map(BookPaths::key)
        assertEquals(
            "Ann Lee/HOME (3).epub",
            BookPaths.path(listOf("Ann Lee"), "HOME") { BookPaths.key(it) in taken },
        )
    }

    @Test
    fun longNamesAreCutAtAWordAndFitEveryFileSystem() {
        val title = "Word ".repeat(60).trim()
        val path = BookPaths.path(listOf("Author"), title)
        val file = path.substringAfter('/')
        assertTrue(file.endsWith(".epub"))
        assertTrue(file.length <= BookPaths.MAX_CHARS)
        assertTrue(file.toByteArray().size <= BookPaths.MAX_BYTES)
        assertTrue(file.removeSuffix(".epub").endsWith("Word"))
    }

    @Test
    fun nonLatinNamesKeepTheirLettersWithinTheByteLimit() {
        assertEquals("村上春樹/ノルウェイの森.epub", BookPaths.path(listOf("村上春樹"), "ノルウェイの森"))
        val long = BookPaths.path(listOf("著者"), "森".repeat(200))
        assertTrue(long.substringAfter('/').toByteArray().size <= BookPaths.MAX_BYTES)
        // A cut never splits a character in two.
        val emoji = BookPaths.part("📚".repeat(100), "x")
        assertTrue(emoji.isNotEmpty())
        assertEquals(emoji.length, emoji.codePoints().toArray().size * 2)
    }

    @Test
    fun composedAndDecomposedAccentsAreOneName() {
        val composed = BookPaths.path(listOf("Gabriel García Márquez"), "Cien años")
        val decomposed =
            BookPaths.path(
                listOf(
                    java.text.Normalizer.normalize(
                        "Gabriel García Márquez",
                        java.text.Normalizer.Form.NFD,
                    )
                ),
                "Cien años",
            )
        assertEquals(composed, decomposed)
        assertEquals(BookPaths.key(composed), BookPaths.key(composed.uppercase()))
    }
}
