package dev.sadking.folio.link

import dev.sadking.folio.core.LibraryRules
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.LibraryDao
import dev.sadking.folio.data.Transfer
import dev.sadking.folio.data.decode
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Folio's library as the first version of the plugin browsed it. Called from the server's own
 * threads. Sync (protocol 2) replaced sending, so nothing is queued for an e-reader any more.
 */
class RoomLinkLibrary(private val dao: LibraryDao) : LinkLibrary {
    // allBooks() already leaves out books in Recently removed. Folio Link serves EPUBs only until
    // sync v2 carries PDFs and comics with their own names and types.
    private fun books(): List<Book> = runBlocking {
        dao.allBooks().filter { it.format == "epub" && File(it.currentPath).isFile }
    }

    private fun Book.link() =
        LinkBook(
            id,
            title,
            decode(authors),
            File(currentPath),
            currentHash,
            addedAt,
            coverPath?.let(::File),
        )

    override fun recent(limit: Int) =
        books().sortedByDescending { it.addedAt }.take(limit).map { it.link() }

    override fun shelves() = runBlocking {
        val all = books()
        val members = dao.allMemberships()
        dao.allShelves().map { shelf ->
            LinkShelf(
                shelf.id,
                shelf.name,
                members(shelf.id, shelf.ruleField, shelf.ruleValue, all, members).size,
            )
        }
    }

    override fun shelf(id: String) = runBlocking {
        val shelf = dao.allShelves().firstOrNull { it.id == id } ?: return@runBlocking emptyList()
        members(shelf.id, shelf.ruleField, shelf.ruleValue, books(), dao.allMemberships()).map {
            it.link()
        }
    }

    private fun members(
        id: String,
        field: String,
        value: String,
        books: List<Book>,
        memberships: List<dev.sadking.folio.data.Membership>,
    ) = books.filter { b ->
        if (field == "Manual") memberships.any { it.bookId == b.id && it.shelfId == id }
        else LibraryRules.matches(field, value, b.metadata(), b.status, false)
    }

    // Nothing is sent any more: sync puts every book on the e-reader.
    override fun queue(device: String): List<LinkBook> = emptyList()

    override fun savedPath(device: String, id: String): String? = runBlocking {
        val book = dao.book(id) ?: return@runBlocking null
        dao.transfers(deviceId(device))
            .firstOrNull { it.bookId == id && it.hash == book.currentHash }
            ?.remotePath
    }

    override fun book(id: String) = books().firstOrNull { it.id == id }?.link()

    override fun acknowledge(device: String, id: String, sha256: String, path: String) =
        runBlocking {
            val book = dao.book(id) ?: return@runBlocking false
            if (book.currentHash != sha256 || dao.removedEntry(id) != null) return@runBlocking false
            dao.transfer(Transfer(id, deviceId(device), path, sha256, System.currentTimeMillis()))
            true
        }

    companion object {
        /** Transfers to a Folio Link e-reader are kept apart from SSH devices. */
        fun deviceId(device: String) = "link:$device"
    }
}
