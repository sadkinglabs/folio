package dev.sadking.folio.data

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class BooksFolderTest {
    private val root = File("/storage/emulated/0")

    @Test
    fun phoneStorageFoldersMapToTheirPath() {
        assertEquals(File(root, "Books"), treePath("primary:Books", root))
        assertEquals(File(root, "Documents/My Books"), treePath("primary:Documents/My Books", root))
    }

    @Test
    fun otherVolumesTheTopLevelAndOddIdsAreRefused() {
        assertNull(treePath("1A2B-3C4D:Books", root))
        assertNull(treePath("primary:", root))
        assertNull(treePath("primary:/", root))
        assertNull(treePath("primary:Books/../Android", root))
        assertNull(treePath("Books", root))
    }
}
