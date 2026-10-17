package dev.sadking.folio.acquire

import org.junit.Assert.*
import org.junit.Test

/** Telling a book download from anything else, and naming it safely (M5). */
class DownloadSnifferTest {
    private val limit = 256L * 1024 * 1024

    @Test
    fun gutenbergEpubByMimeType() {
        val url = "https://www.gutenberg.org/ebooks/1342.epub3.images"
        assertEquals(DownloadKind.BOOK, DownloadSniffer.kind(url, null, "application/epub+zip"))
        assertEquals(
            "1342.epub3.images.epub",
            DownloadSniffer.fileName(url, null, "application/epub+zip"),
        )
    }

    @Test
    fun standardEbooksEpubWithAQueryString() {
        val url =
            "https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice/downloads/jane-austen_pride-and-prejudice.epub?source=download"
        assertEquals(DownloadKind.BOOK, DownloadSniffer.kind(url, null, "application/epub+zip"))
        assertEquals(DownloadKind.BOOK, DownloadSniffer.kind(url, null, null))
        assertEquals(
            "jane-austen_pride-and-prejudice.epub",
            DownloadSniffer.fileName(url, null, "application/epub+zip"),
        )
    }

    @Test
    fun contentDispositionNameWins() {
        val url = "https://archive.org/download/x/file"
        val header =
            "attachment; filename=\"plain.epub\"; filename*=UTF-8''Caf%C3%A9%20Stories.epub"
        assertEquals(
            DownloadKind.BOOK,
            DownloadSniffer.kind(url, header, "application/octet-stream"),
        )
        assertEquals(
            "Café Stories.epub",
            DownloadSniffer.fileName(url, header, "application/octet-stream"),
        )
        assertEquals(
            "plain.epub",
            DownloadSniffer.fileName(url, "attachment; filename=\"plain.epub\"", null),
        )
        assertEquals(
            "token.pdf",
            DownloadSniffer.fileName(url, "attachment; filename=token.pdf", null),
        )
    }

    @Test
    fun genericTypeDecidesByExtension() {
        val url = "https://example.org/comics/Issue%201.cbr"
        assertEquals(
            DownloadKind.BOOK,
            DownloadSniffer.kind(url, null, "application/octet-stream"),
        )
        assertEquals(
            DownloadKind.BOOK,
            DownloadSniffer.kind(url, null, "application/x-rar-compressed"),
        )
        assertEquals("Issue 1.cbr", DownloadSniffer.fileName(url, null, "application/octet-stream"))
        // A type that says what the file is overrides the name.
        assertEquals(DownloadKind.OTHER, DownloadSniffer.kind(url, null, "text/html"))
    }

    @Test
    fun acsmIsRecognisedAndNeverFetched() {
        assertEquals(
            DownloadKind.ACSM,
            DownloadSniffer.kind(
                "https://shop.example/fulfil",
                null,
                "application/vnd.adobe.adept+xml",
            ),
        )
        assertEquals(
            DownloadKind.ACSM,
            DownloadSniffer.kind("https://shop.example/URLLink.acsm", null, "application/xml"),
        )
        assertEquals(
            DownloadDecision.Refuse(AcquireOutcome.Protected),
            DownloadSniffer.decide("https://shop.example/URLLink.acsm", null, null, -1, limit),
        )
    }

    @Test
    fun htmlAndOtherFilesAreNotBooks() {
        assertEquals(
            DownloadKind.OTHER,
            DownloadSniffer.kind("https://example.org/page", null, "text/html"),
        )
        assertEquals(
            DownloadKind.OTHER,
            DownloadSniffer.kind("https://example.org/book.mobi", null, "application/x-mobipocket"),
        )
        assertEquals(
            DownloadKind.OTHER,
            DownloadSniffer.kind("https://example.org/book.azw3", null, "application/octet-stream"),
        )
        assertEquals(
            DownloadDecision.NotABook,
            DownloadSniffer.decide("https://example.org/page", null, "text/html", -1, limit),
        )
        // A large file that isn't a book is "not a book", not "too large".
        assertEquals(
            DownloadDecision.NotABook,
            DownloadSniffer.decide("https://e.org/v.mp4", null, "video/mp4", limit + 1, limit),
        )
    }

    @Test
    fun unsafeNamesAreCleaned() {
        assertEquals(
            "x.epub",
            DownloadSniffer.fileName("https://e.org/a", "attachment; filename=\"../x.epub\"", null),
        )
        assertEquals(
            "x.epub",
            DownloadSniffer.fileName(
                "https://e.org/a",
                "attachment; filename=\"..\\\\x.epub\"",
                null,
            ),
        )
        assertEquals(
            "hidden.epub",
            DownloadSniffer.fileName("https://e.org/.hidden.epub", null, null),
        )
        assertEquals(
            "ab.epub",
            DownloadSniffer.fileName("https://e.org/a%00%1F%7Fb.epub", null, null),
        )
        assertEquals(
            "book.epub",
            DownloadSniffer.fileName("https://e.org/", null, "application/epub+zip"),
        )
        val long = "a".repeat(300) + ".epub"
        val name = DownloadSniffer.fileName("https://e.org/$long", null, null)
        assertEquals(120, name.length)
        assertTrue(name.endsWith(".epub"))
    }

    @Test
    fun decideUpgradesHttpAndRefusesOtherSchemesAndHugeFiles() {
        assertEquals(
            DownloadDecision.Fetch("https://example.org/b.epub", "b.epub"),
            DownloadSniffer.decide("http://example.org/b.epub", null, null, -1, limit),
        )
        assertEquals(
            DownloadDecision.Refuse(AcquireOutcome.Failed),
            DownloadSniffer.decide(
                "blob:https://example.org/1",
                null,
                "application/epub+zip",
                -1,
                limit,
            ),
        )
        assertEquals(
            DownloadDecision.Refuse(AcquireOutcome.TooLarge),
            DownloadSniffer.decide("https://example.org/b.pdf", null, null, limit + 1, limit),
        )
    }

    @Test
    fun withExtensionAddsTheDetectedFormatOnce() {
        assertEquals("a.epub", DownloadSniffer.withExtension("a.epub", "epub"))
        assertEquals("a.EPUB", DownloadSniffer.withExtension("a.EPUB", "epub"))
        assertEquals("a.zip.cbz", DownloadSniffer.withExtension("a.zip", "cbz"))
    }
}
