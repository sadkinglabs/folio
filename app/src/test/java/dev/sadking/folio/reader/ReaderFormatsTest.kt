package dev.sadking.folio.reader

import org.junit.Assert.*
import org.junit.Test

class ReaderFormatsTest {
    @Test
    fun eachStoredFormatHasItsReader() {
        assertEquals(ReaderKind.EPUB, readerKind("epub"))
        assertEquals(ReaderKind.PDF, readerKind("PDF"))
        assertEquals(ReaderKind.COMIC, readerKind("cbz"))
        assertEquals(ReaderKind.COMIC, readerKind("cbr"))
        assertNull(readerKind("mobi"))
        assertNull(readerKind(""))
        assertTrue(opensOnPhone("cbr"))
        assertFalse(opensOnPhone("azw3"))
    }

    @Test
    fun onlyPicturesBecomeComicPages() {
        assertEquals("001.jpg", comicPageName("001.jpg"))
        assertEquals("Ch 1/002.PNG", comicPageName("Ch 1/002.PNG"))
        assertEquals("a/b.webp", comicPageName("a\\b.webp"))
        assertEquals("x.jpeg", comicPageName("/x.jpeg"))
        assertNull(comicPageName("ComicInfo.xml"))
        assertNull(comicPageName("__MACOSX/._001.jpg"))
        assertNull(comicPageName(".cover.jpg"))
        assertNull(comicPageName("Thumbs.db"))
        assertNull(comicPageName("folder/"))
    }
}
