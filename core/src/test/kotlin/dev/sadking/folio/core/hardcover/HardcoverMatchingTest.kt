package dev.sadking.folio.core.hardcover

import dev.sadking.folio.core.hardcover.HardcoverMatching as M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardcoverMatchingTest {
    private fun query(title: String, vararg authors: String, isbns: List<String> = emptyList()) =
        MatchQuery("item", title, authors.toList(), isbns)

    private fun book(id: Int, title: String, vararg authors: String, year: Int? = null) =
        MatchCandidate(id, title, authors.toList(), year, null)

    // Normalisation.

    @Test
    fun accentsAndCaseDoNotMatter() {
        assertEquals(
            M.normalizeTitle("Cien años de soledad"),
            M.normalizeTitle("Cien anos de soledad"),
        )
        assertEquals("cien anos de soledad", M.normalizeTitle("CIEN AÑOS DE SOLEDAD"))
        assertEquals(
            M.normalizeAuthor("Gabriel García Márquez"),
            M.normalizeAuthor("Gabriel Garcia Marquez"),
        )
    }

    @Test
    fun punctuationAmpersandAndApostrophes() {
        assertEquals("pride and prejudice", M.normalizeTitle("Pride & Prejudice"))
        assertEquals("enders game", M.normalizeTitle("Ender’s Game"))
        assertEquals("enders game", M.normalizeTitle("Ender's  Game!"))
        assertEquals("1984", M.normalizeTitle("1984"))
    }

    @Test
    fun leadingArticleIsDroppedOnce() {
        assertEquals("hobbit", M.normalizeTitle("The Hobbit"))
        assertEquals("wrinkle in time", M.normalizeTitle("A Wrinkle in Time"))
        assertEquals("unkindness of ghosts", M.normalizeTitle("An Unkindness of Ghosts"))
        assertEquals("the end", M.normalizeTitle("The The End"))
        assertEquals("the", M.normalizeTitle("The"))
        assertEquals("theory of everything", M.normalizeTitle("Theory of Everything"))
    }

    @Test
    fun trailingSeriesInBracketsIsDropped() {
        assertEquals("leviathan wakes", M.normalizeTitle("Leviathan Wakes (The Expanse #1)"))
        assertEquals("leviathan wakes", M.normalizeTitle("Leviathan Wakes [Expanse, 1]"))
        assertEquals("1984", M.normalizeTitle("(1984)"))
    }

    @Test
    fun mainTitleStopsAtTheSubtitle() {
        assertEquals("sapiens", M.mainTitle("Sapiens: A Brief History of Humankind"))
        assertEquals("dune", M.mainTitle("Dune - Deluxe Edition"))
        assertEquals("dune", M.mainTitle("Dune — Deluxe Edition"))
        assertEquals("leviathan wakes", M.mainTitle("Leviathan Wakes (The Expanse #1)"))
        assertEquals("spider man", M.mainTitle("Spider-Man"))
        // A comma is not a subtitle mark.
        assertEquals(
            "hobbit or there and back again",
            M.mainTitle("The Hobbit, or There and Back Again"),
        )
    }

    @Test
    fun lastFirstBecomesFirstLast() {
        assertEquals("ursula k le guin", M.normalizeAuthor("Le Guin, Ursula K."))
        assertEquals(
            M.normalizeAuthor("Ursula K. Le Guin"),
            M.normalizeAuthor("Le Guin, Ursula K."),
        )
        assertEquals("gabriel garcia marquez", M.normalizeAuthor("García Márquez, Gabriel"))
    }

    @Test
    fun initialsAreJoined() {
        assertEquals("jrr tolkien", M.normalizeAuthor("J. R. R. Tolkien"))
        assertEquals("jrr tolkien", M.normalizeAuthor("J.R.R. Tolkien"))
        assertEquals("jrr tolkien", M.normalizeAuthor("JRR Tolkien"))
        assertEquals("jrr tolkien", M.normalizeAuthor("Tolkien, J. R. R."))
        assertEquals("nk jemisin", M.normalizeAuthor("N. K. Jemisin"))
    }

    // ISBNs.

    @Test
    fun isbn13IsCheckedAndCleaned() {
        assertEquals("9780306406157", M.isbn13("9780306406157"))
        assertEquals("9780306406157", M.isbn13("978-0-306-40615-7"))
        assertEquals("9780306406157", M.isbn13(" 978 0 306 40615 7 "))
        assertEquals("9780306406157", M.isbn13("urn:isbn:978-0-306-40615-7"))
        assertEquals("9780306406157", M.isbn13("URN:ISBN:9780306406157"))
        assertEquals("9780306406157", M.isbn13("isbn:9780306406157"))
        assertEquals("9780306406157", M.isbn13("ISBN 978-0-306-40615-7"))
        assertEquals("9780306406157", M.isbn13("ISBN-13: 978-0-306-40615-7"))
        assertEquals("9780306406157", M.isbn13("ISBN978-0-306-40615-7"))
    }

    @Test
    fun badCheckDigitsAreRejected() {
        assertNull(M.isbn13("9780306406158"))
        assertNull(M.isbn13("0306406153"))
        assertNull(M.isbn13("0804429579"))
    }

    @Test
    fun otherIdentifiersAreNotIsbns() {
        assertNull(M.isbn13(""))
        assertNull(M.isbn13("urn:uuid:12345678-1234-1234-1234-123456789012"))
        assertNull(M.isbn13("calibre:1234"))
        assertNull(M.isbn13("amazon:B00ABCDEFG"))
        assertNull(M.isbn13("123456789"))
        assertNull(M.isbn13("97803064061570"))
        // Valid checksum, but not a Bookland prefix.
        assertNull(M.isbn13("1234567890128"))
        assertNull(M.isbn13("X306406152"))
    }

    @Test
    fun isbn10BecomesIsbn13() {
        assertEquals("9780306406157", M.isbn13("0306406152"))
        assertEquals("9780306406157", M.isbn13("0-306-40615-2"))
        assertEquals("9780306406157", M.isbn13("ISBN-10: 0-306-40615-2"))
        assertEquals("9780306406157", M.isbn13("urn:isbn:0306406152"))
    }

    @Test
    fun isbn10WithXCheckDigit() {
        assertEquals("9780804429573", M.isbn13("080442957X"))
        assertEquals("9780804429573", M.isbn13("0-8044-2957-x"))
        assertEquals("9780439420891", M.isbn13("urn:isbn:043942089X"))
    }

    @Test
    fun isbn979IsKept() {
        assertEquals("9791000000008", M.isbn13("979-10-00000-00-8"))
        assertEquals(listOf("9791000000008"), M.isbnForms("9791000000008"))
    }

    @Test
    fun isbnsAreDistinctAndInOrder() {
        assertEquals(
            listOf("9780804429573", "9780306406157"),
            M.isbns(
                listOf(
                    "urn:uuid:abc",
                    "080442957X",
                    "9780306406157",
                    "0306406152",
                    "9780306406158",
                    "urn:isbn:978-0-8044-2957-3",
                )
            ),
        )
        assertEquals(emptyList<String>(), M.isbns(emptyList()))
    }

    @Test
    fun isbnFormsAddTheIsbn10() {
        assertEquals(listOf("9780306406157", "0306406152"), M.isbnForms("9780306406157"))
        assertEquals(listOf("9780804429573", "080442957X"), M.isbnForms("9780804429573"))
    }

    // Scores.

    @Test
    fun scoresTitleAndAuthor() {
        val q = query("The Hobbit", "J. R. R. Tolkien")
        assertEquals(4, M.score(q, book(1, "Hobbit", "JRR Tolkien")))
        assertEquals(
            4,
            M.score(q, book(1, "The Hobbit: Or There and Back Again", "Tolkien, J.R.R.")),
        )
        assertEquals(3, M.score(q, book(1, "The Hobbit", "Christopher Tolkien")))
        assertEquals(2, M.score(q, book(1, "The Hobbit", "Someone Else")))
        assertEquals(2, M.score(q, book(1, "The Silmarillion", "J.R.R. Tolkien")))
        assertEquals(0, M.score(q, book(1, "Dune", "Frank Herbert")))
    }

    @Test
    fun containedMainTitleScoresOne() {
        val q = query("Foundation", "Isaac Asimov")
        assertEquals(3, M.score(q, book(1, "Foundation and Empire", "Isaac Asimov")))
        // Short main titles are not enough, and parts of words never count.
        assertEquals(0, M.score(query("It"), book(1, "It Ends with Us")))
        assertEquals(0, M.score(query("Dune"), book(1, "Dunes of Mars")))
        assertEquals(1, M.score(query("Dune"), book(1, "Dune Messiah")))
    }

    @Test
    fun unknownAuthorsScoreNothing() {
        val candidate = book(1, "Hobbit", "Unknown")
        assertEquals(2, M.score(query("The Hobbit", "Unknown author"), candidate))
        assertEquals(2, M.score(query("The Hobbit", ""), book(1, "Hobbit", "J.R.R. Tolkien")))
        assertEquals(2, M.score(query("The Hobbit"), book(1, "Hobbit", "J.R.R. Tolkien")))
        assertEquals(2, M.score(query("The Hobbit", "Unknown"), book(1, "Hobbit")))
    }

    @Test
    fun blankTitlesNeverMatch() {
        assertEquals(0, M.score(query(""), book(1, "")))
        assertEquals(2, M.score(query("", "Frank Herbert"), book(1, "", "Frank Herbert")))
    }

    // ISBN decisions.

    @Test
    fun oneIsbnBookThatAgreesIsConfident() {
        val hobbit = book(1, "The Hobbit", "J.R.R. Tolkien")
        assertEquals(
            MatchDecision.Confident(hobbit),
            M.fromIsbn(query("Hobbit", "Someone Else"), listOf(hobbit)),
        )
        assertEquals(
            MatchDecision.Confident(hobbit),
            M.fromIsbn(query("Der kleine Hobbit", "J. R. R. Tolkien"), listOf(hobbit)),
        )
    }

    @Test
    fun oneIsbnBookThatDisagreesIsUnsure() {
        val other = book(1, "Some Other Book", "Someone Else")
        assertEquals(
            MatchDecision.Unsure(listOf(other)),
            M.fromIsbn(query("The Hobbit", "J. R. R. Tolkien"), listOf(other)),
        )
    }

    @Test
    fun theSameIsbnBookTwiceCountsOnce() {
        val hobbit = book(1, "The Hobbit", "J.R.R. Tolkien")
        assertEquals(
            MatchDecision.Confident(hobbit),
            M.fromIsbn(
                query("The Hobbit", "Tolkien"),
                listOf(hobbit, hobbit.copy(releaseYear = 1937)),
            ),
        )
    }

    @Test
    fun severalIsbnBooksAreUnsureBestFirst() {
        val a = book(1, "Unrelated", "Nobody")
        val b = book(2, "The Hobbit", "J.R.R. Tolkien")
        val c = book(3, "The Hobbit", "Someone")
        val d = book(4, "Hobbit", "Nobody")
        assertEquals(
            MatchDecision.Unsure(listOf(b, c, d)),
            M.fromIsbn(query("The Hobbit", "J. R. R. Tolkien"), listOf(a, b, c, d)),
        )
        assertEquals(
            MatchDecision.Unsure(listOf(a, d.copy(title = "Other"))),
            M.fromIsbn(query("The Hobbit"), listOf(a, d.copy(title = "Other"))),
        )
    }

    @Test
    fun anIsbnOfAnotherBookInTheSeriesIsCheckedWithTheTitleSearch() {
        // A sequel's EPUB that carries the first book's ISBN (a common metadata slip).
        val hyperion = book(1, "Hyperion", "Dan Simmons", year = 1989)
        val fallBook = book(2, "The Fall of Hyperion", "Dan Simmons", year = 1990)
        val fall = query("The Fall of Hyperion", "Dan Simmons")
        assertEquals(3, M.score(fall, hyperion))
        assertTrue(M.seriesSibling(fall, hyperion))
        val isbn = M.fromIsbn(fall, listOf(hyperion)) as MatchDecision.Confident
        assertTrue(M.needsSearch(fall, isbn))
        // The search finds the book itself: that wins.
        assertEquals(
            MatchDecision.Confident(fallBook),
            M.confirmIsbn(fall, isbn, listOf(hyperion, fallBook)),
        )
        // The search finds nothing better, or cannot be made: never linked, left to the owner.
        assertEquals(MatchDecision.Unsure(listOf(hyperion)), M.confirmIsbn(fall, isbn, listOf()))
        assertEquals(MatchDecision.Unsure(listOf(hyperion)), M.confirmIsbn(fall, isbn, null))
        assertEquals(
            MatchDecision.Unsure(listOf(hyperion)),
            M.confirmIsbn(fall, isbn, listOf(hyperion)),
        )
        // An ISBN book with the same title, or another title altogether, needs no search.
        val same = query("Hyperion", "Dan Simmons")
        assertFalse(M.needsSearch(same, M.fromIsbn(same, listOf(hyperion))))
        val translated = query("Las Torres del Tiempo", "Dan Simmons")
        assertFalse(M.needsSearch(translated, M.fromIsbn(translated, listOf(hyperion))))
    }

    @Test
    fun noIsbnBookIsNotFound() {
        assertEquals(MatchDecision.NotFound, M.fromIsbn(query("The Hobbit"), emptyList()))
    }

    // Search decisions.

    @Test
    fun searchIsConfidentOnlyWhenOneResultClearlyWins() {
        val q = query("Leviathan Wakes (The Expanse #1)", "James S. A. Corey")
        val right = book(10, "Leviathan Wakes", "James S.A. Corey")
        val companion = book(11, "Leviathan Wakes: Companion", "Someone Else")
        val series = book(12, "Caliban's War", "James S.A. Corey")
        assertEquals(
            MatchDecision.Confident(right),
            M.fromSearch(q, listOf(companion, series, right)),
        )
    }

    @Test
    fun twoPerfectResultsAreUnsure() {
        val q = query("Dune", "Frank Herbert")
        val one = book(1, "Dune", "Frank Herbert", year = 1965)
        val two = book(2, "Dune", "Frank Herbert", year = 2005)
        assertEquals(MatchDecision.Unsure(listOf(one, two)), M.fromSearch(q, listOf(one, two)))
    }

    @Test
    fun aCloseRunnerUpMakesItUnsure() {
        val q = query("Dune", "Frank Herbert")
        val one = book(1, "Dune", "Frank Herbert")
        val two = book(2, "Dune Messiah", "Frank Herbert")
        val three = book(3, "Dune", "Brian Herbert")
        assertEquals(
            MatchDecision.Unsure(listOf(one, two, three)),
            M.fromSearch(q, listOf(two, three, one)),
        )
    }

    @Test
    fun aSeriesSiblingIsNeverTakenForTheBook() {
        val hyperion = book(1, "Hyperion", "Dan Simmons", year = 1989)
        val fall = book(2, "The Fall of Hyperion", "Dan Simmons", year = 1990)
        val cantos = book(3, "Hyperion Cantos", "Dan Simmons")
        // Each book links to its own entry; the other book of the series is not a rival.
        assertEquals(
            MatchDecision.Confident(fall),
            M.fromSearch(query("The Fall of Hyperion", "Dan Simmons"), listOf(hyperion, fall)),
        )
        assertEquals(
            MatchDecision.Confident(hyperion),
            M.fromSearch(query("Hyperion", "Dan Simmons"), listOf(fall, cantos, hyperion)),
        )
        // With only the sibling found, the sequel is never linked to the first book.
        assertEquals(
            MatchDecision.Unsure(listOf(hyperion)),
            M.fromSearch(query("The Fall of Hyperion", "Dan Simmons"), listOf(hyperion)),
        )
        assertEquals(
            null,
            M.fromLibrary(query("The Fall of Hyperion", "Dan Simmons"), listOf(hyperion)),
        )
    }

    @Test
    fun unsureKeepsThreePlausibleResultsInHardcoversOrderOnTies() {
        val q = query("The Hobbit")
        val results = (1..5).map { book(it, "The Hobbit", "Author $it") } + book(6, "Dune")
        assertEquals(
            MatchDecision.Unsure(results.take(3)),
            M.fromSearch(q, results),
        )
    }

    @Test
    fun withoutAnAuthorSearchIsNeverConfident() {
        val hobbit = book(1, "The Hobbit", "J.R.R. Tolkien")
        assertEquals(
            MatchDecision.Unsure(listOf(hobbit)),
            M.fromSearch(query("The Hobbit", "Unknown author"), listOf(hobbit)),
        )
    }

    @Test
    fun nothingPlausibleIsNotFound() {
        val q = query("The Hobbit", "J. R. R. Tolkien")
        assertEquals(MatchDecision.NotFound, M.fromSearch(q, emptyList()))
        assertEquals(
            MatchDecision.NotFound,
            M.fromSearch(q, listOf(book(1, "Dune", "Frank Herbert"), book(2, "Hobbits", "Tolk"))),
        )
    }

    // The owner's library.

    @Test
    fun libraryMatchNeedsOneExactBook() {
        val q = query("Cien años de soledad", "García Márquez, Gabriel")
        val book = book(1, "Cien anos de soledad", "Gabriel Garcia Marquez")
        val other = book(2, "Cien años de soledad", "Someone Else")
        assertEquals(book, M.fromLibrary(q, listOf(other, book)))
        assertEquals(book, M.fromLibrary(q, listOf(book, book)))
        assertNull(M.fromLibrary(q, listOf(other)))
        assertNull(M.fromLibrary(q, listOf(book, book.copy(hardcoverId = 3))))
        assertNull(M.fromLibrary(query("Cien años de soledad"), listOf(book)))
        assertNull(M.fromLibrary(q, emptyList()))
    }

    // Search text and metadata key.

    @Test
    fun searchTextIsMainTitleAndFirstUsableAuthor() {
        assertEquals(
            "Sapiens Yuval Noah Harari",
            M.searchText(
                query("Sapiens: A Brief History of Humankind", "Yuval Noah Harari", "Other")
            ),
        )
        assertEquals(
            "Leviathan Wakes James S. A. Corey",
            M.searchText(
                query("Leviathan Wakes (The Expanse #1)", "Unknown", "Corey, James S. A.")
            ),
        )
        assertEquals(
            "Cien años de soledad",
            M.searchText(query("Cien  años de soledad", "Unknown author")),
        )
        assertEquals("", M.searchText(query("  ", "Frank Herbert")))
        assertEquals(300, M.searchText(query("x".repeat(400), "Frank Herbert")).length)
    }

    @Test
    fun metadataKeyIgnoresOrderAndCosmeticChanges() {
        val key =
            M.metadataKey(
                "The Hobbit",
                listOf("J. R. R. Tolkien", "Alan Lee"),
                listOf("9780306406157", "9780804429573"),
            )
        assertTrue(key.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            key,
            M.metadataKey(
                "hobbit",
                listOf("Lee, Alan", "JRR Tolkien"),
                listOf("9780804429573", "9780306406157"),
            ),
        )
        assertNotEquals(
            key,
            M.metadataKey(
                "The Hobbit",
                listOf("J. R. R. Tolkien"),
                listOf("9780306406157", "9780804429573"),
            ),
        )
        assertNotEquals(
            key,
            M.metadataKey(
                "The Hobbit",
                listOf("J. R. R. Tolkien", "Alan Lee"),
                listOf("9780306406157"),
            ),
        )
        assertNotEquals(
            key,
            M.metadataKey(
                "The Hobbit 2",
                listOf("J. R. R. Tolkien", "Alan Lee"),
                listOf("9780306406157", "9780804429573"),
            ),
        )
        // Moving a value from one field to another changes the key.
        assertNotEquals(
            M.metadataKey("A", listOf("B"), emptyList()),
            M.metadataKey("A B", emptyList(), emptyList()),
        )
    }
}
