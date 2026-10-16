package dev.sadking.folio.link

import android.content.Context
import dev.sadking.folio.core.LibraryRules
import dev.sadking.folio.data.Book
import dev.sadking.folio.data.LibraryDao
import dev.sadking.folio.data.Transfer
import dev.sadking.folio.data.decode
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Books waiting for Folio Link e-readers. Each Link e-reader fetches everything here that it does
 * not have yet; a confirmed download is recorded as a transfer for that device. Entries older than
 * 30 days are dropped.
 */
class LinkQueue(context: Context) {
    private val prefs = context.getSharedPreferences("link-queue", Context.MODE_PRIVATE)
    private val _ids = MutableStateFlow(ids())
    /** The queued book ids, oldest first, for the Device tab. */
    val queued: StateFlow<List<String>> = _ids

    @Synchronized
    fun add(ids: Collection<String>) {
        val queued = load()
        val now = System.currentTimeMillis()
        ids.forEach { queued.putIfAbsent(it, now) }
        save(queued)
    }

    /** Takes a book out of the queue; e-readers that already have it keep it. */
    @Synchronized
    fun remove(id: String) {
        val queued = load()
        if (queued.remove(id) != null) save(queued)
    }

    @Synchronized
    fun ids(): List<String> {
        val queued = load()
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        if (queued.values.removeAll { it < cutoff }) save(queued)
        return queued.entries.sortedBy { it.value }.map { it.key }
    }

    private fun load(): MutableMap<String, Long> =
        runCatching {
                val o = JSONObject(prefs.getString("books", "{}")!!)
                o.keys().asSequence().associateWith { o.getLong(it) }.toMutableMap()
            }
            .getOrDefault(mutableMapOf())

    private fun save(value: Map<String, Long>) {
        prefs.edit().putString("books", JSONObject(value).toString()).apply()
        _ids.value = value.entries.sortedBy { it.value }.map { it.key }
    }
}

/** Folio's library as Folio Link serves it. Called from the server's own threads. */
class RoomLinkLibrary(private val dao: LibraryDao, private val queue: LinkQueue) : LinkLibrary {
    // allBooks() already leaves out books in Recently removed.
    private fun books(): List<Book> = runBlocking {
        dao.allBooks().filter { File(it.currentPath).isFile }
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

    // Every queued book, delivered or not: the plugin checks the file is still where it was saved
    // and fetches it again if it is not, instead of trusting this record.
    override fun queue(device: String): List<LinkBook> {
        val byId = books().associateBy { it.id }
        return queue.ids().mapNotNull { byId[it] }.map { it.link() }
    }

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
