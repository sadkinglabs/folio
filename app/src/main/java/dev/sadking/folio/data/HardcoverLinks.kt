package dev.sadking.folio.data

import androidx.room.withTransaction
import dev.sadking.folio.R
import dev.sadking.folio.sync.HardcoverStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Links a book or a catalogue entry to a Hardcover book, the way the manual link in the Hardcover
 * sheet does (DiscoveryViewModel.link). Used by automatic matching and by "Check these matches"
 * (docs/HARDCOVER_SYNC.md, A2 and B5). Linking only changes Librillo; Hardcover hears about the
 * book through status sync.
 */
class HardcoverLinks(private val repo: LibraryRepository) {
    private val dao = repo.dao
    private val store =
        HardcoverStore(repo.database, File(repo.context.filesDir, HardcoverStore.COVER_DIR))

    /**
     * Links [itemId] (a book id or a catalogue entry id) to Hardcover book [hardcoverId], as
     * DiscoveryViewModel.link does, under repo.lock in one transaction. Returns the id of the
     * catalogue entry that carries the link, and deletes the item's MatchRecord. Throws
     * IllegalArgumentException with a plain sentence when the item is gone or in Recently removed,
     * when that Hardcover book's catalogue entry is in Recently removed, or when that Hardcover
     * book is already linked to another book.
     */
    suspend fun link(
        itemId: String,
        hardcoverId: Int,
        title: String,
        authors: List<String>,
    ): String {
        require(hardcoverId > 0)
        val (entryId, touched) =
            withContext(Dispatchers.IO) {
                repo.lock.withLock {
                    repo.database.withTransaction {
                        linkLocked(itemId, hardcoverId, title, authors)
                    }
                }
            }
        // The item, and whichever of its book or entry matching knew it by, no longer need a match.
        (touched + itemId).forEach { store.deleteMatch(it) }
        return entryId
    }

    /** The linking entry's id, and the ids it and its book go by. */
    private suspend fun linkLocked(
        itemId: String,
        hardcoverId: Int,
        title: String,
        authors: List<String>,
    ): Pair<String, Set<String>> {
        val book = dao.book(itemId)
        val wish = if (book == null) dao.wish(itemId) else null
        require(book != null || wish != null) { text(R.string.msg_book_gone) }
        require(dao.removedEntry(itemId) == null) { text(R.string.msg_library_restore_first) }
        val holders = dao.allStoredWishes().filter { it.hardcoverId == hardcoverId }
        require(holders.none { dao.removedEntry(it.id) != null }) {
            text(R.string.msg_discovery_removed_before_linking)
        }
        val entry = wish ?: dao.allWishes().firstOrNull { it.linkedBookId == book!!.id }
        if (entry != null) {
            require(entry.linkedBookId?.let { dao.removedEntry(it) } == null) {
                text(R.string.msg_library_restore_first)
            }
            val ids = setOfNotNull(entry.id, entry.linkedBookId)
            if (entry.hardcoverId == hardcoverId) return entry.id to ids
            require(holders.none { it.id != entry.id }) {
                text(R.string.msg_discovery_hardcover_book_exists)
            }
            dao.updateWish(
                entry.copy(
                    hardcoverId = hardcoverId,
                    hardcoverTitle = title.trim().ifBlank { entry.title }.take(MAX_TITLE),
                    hardcoverSynced = false,
                )
            )
            return entry.id to ids
        }
        book!!
        require(holders.size <= 1) { text(R.string.msg_discovery_duplicates) }
        val holder = holders.singleOrNull()
        if (holder != null) {
            // Hardcover's book is already a catalogue entry: the file joins it, if it has none.
            require(holder.linkedBookId == null) { text(R.string.msg_discovery_linked_elsewhere) }
            requireAttachment(holder, book, dao.allWishes())
            dao.updateWish(holder.copy(linkedBookId = book.id))
            dao.allWishMemberships()
                .filter { it.wishId == holder.id }
                .forEach { dao.addMembership(Membership(book.id, it.shelfId)) }
            return holder.id to setOf(holder.id, book.id)
        }
        val id = UUID.randomUUID().toString()
        val folder = File(repo.root, id)
        try {
            val cover =
                book.coverPath
                    ?.let(::File)
                    ?.takeIf { it.isFile }
                    ?.let { source ->
                        runCatching {
                                folder.mkdirs()
                                source.copyTo(File(folder, "wish-cover.jpg")).absolutePath
                            }
                            .getOrNull()
                    }
            val name = title.trim().ifBlank { book.title }.take(MAX_TITLE)
            val entryValue =
                WishBook(
                    id,
                    hardcoverCatalogKey(repo.database.hardcover(), hardcoverId, id),
                    name,
                    encode(authors.map { it.take(MAX_AUTHOR) }.take(MAX_AUTHORS)),
                    cover,
                    hardcoverId,
                    name,
                    linkedBookId = book.id,
                )
            parseWish(wishJson(entryValue))
            require(dao.insertWish(entryValue) != -1L) {
                text(R.string.msg_discovery_hardcover_book_exists)
            }
            return id to setOf(id, book.id)
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
    }

    private fun text(id: Int) = repo.context.getString(id)

    companion object {
        const val MAX_TITLE = 2000
        const val MAX_AUTHOR = 1000
        const val MAX_AUTHORS = 100
    }
}

/**
 * A free catalogue key for a new entry for Hardcover book [hardcoverId]: "hardcover:<id>", or
 * "manual:<entry id>" when an entry that was relinked to another Hardcover book still holds it.
 */
internal suspend fun hardcoverCatalogKey(dao: HardcoverDao, hardcoverId: Int, entryId: String) =
    "hardcover:$hardcoverId".takeUnless { dao.catalogKeyTaken(it) } ?: "manual:$entryId"
