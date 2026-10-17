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
 * Who already has a Hardcover book, as linking sees it: the catalogue entry that carries it, the
 * book that entry is attached to (null for an entry without a file), the title to show for it, and
 * whether it is in Recently removed.
 */
data class HardcoverHolder(
    val entryId: String,
    val bookId: String?,
    val title: String,
    val removed: Boolean,
)

/**
 * The one rule for "is this Hardcover book free for this item?", used by automatic matching, Check
 * these matches and the Hardcover sheet alike. Returns null when [itemId] (a book id or a catalogue
 * entry id) can take Hardcover book [hardcoverId] without taking it from anything else, or the
 * holder it would be taken from.
 * - An item with its own catalogue entry: any other entry that carries the Hardcover book holds it.
 * - A book without one: it joins an entry that has no file, or whose file's book no longer exists
 *   (a stale link, repaired by linking). An entry attached to another book, or one in Recently
 *   removed, holds it.
 *
 * [books] maps ids to every stored book, Recently removed included; a book missing from it no
 * longer exists. [wishes] are every stored catalogue entry; [removed] the ids in Recently removed.
 */
fun hardcoverHolder(
    itemId: String,
    hardcoverId: Int,
    books: Map<String, Book>,
    wishes: List<WishBook>,
    removed: Set<String>,
): HardcoverHolder? {
    val own =
        wishes.firstOrNull { it.id == itemId }
            ?: wishes.firstOrNull { it.linkedBookId == itemId && it.id !in removed }
    val holder =
        wishes.firstOrNull { it.hardcoverId == hardcoverId && it.id != own?.id } ?: return null
    val book = holder.linkedBookId?.let(books::get)
    val gone = holder.id in removed || (book != null && book.id in removed)
    if (own == null && !gone && book == null) return null
    return HardcoverHolder(holder.id, book?.id, book?.title ?: holder.title, gone)
}

/**
 * Hardcover's book is already matched to [holder]. The message says so in a plain sentence; the
 * owner can then move the match ([HardcoverLinks.link] with move) or keep it where it is.
 */
class HardcoverLinkTaken(val holder: HardcoverHolder, message: String) :
    IllegalArgumentException(message)

/**
 * A link that waits for the owner because Hardcover book [hardcoverId] is already matched to
 * [holder]: "Move the match here" links [itemId] with move, "Keep it there" drops the offer.
 */
data class MoveOffer(
    val itemId: String,
    val hardcoverId: Int,
    val title: String,
    val authors: List<String>,
    val holder: HardcoverHolder,
)

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
     * Links [itemId] (a book id or a catalogue entry id) to Hardcover book [hardcoverId], under
     * repo.lock in one transaction. Returns the id of the catalogue entry that carries the link,
     * and deletes the MatchRecord of the item and of anything the link was moved from.
     *
     * Throws IllegalArgumentException with a plain sentence when the item is gone or in Recently
     * removed, and [HardcoverLinkTaken] when the Hardcover book is already matched to something
     * else ([hardcoverHolder]) and [move] is false. With [move], the match moves here: a book
     * without its own entry takes the Hardcover book's entry (out of Recently removed if it was
     * there) with its lists; an item with its own entry takes the Hardcover book from the other
     * entry. Whatever the match leaves keeps its file, notes, progress, lists and status, and goes
     * back to matching. Nothing is deleted.
     */
    suspend fun link(
        itemId: String,
        hardcoverId: Int,
        title: String,
        authors: List<String>,
        move: Boolean = false,
    ): String {
        require(hardcoverId > 0)
        val (entryId, touched) =
            withContext(Dispatchers.IO) {
                repo.lock.withLock {
                    repo.database.withTransaction {
                        linkLocked(itemId, hardcoverId, title, authors, move)
                    }
                }
            }
        // The item, whatever the match left, and the ids matching knew them by: no stale records.
        (touched + itemId).forEach { store.deleteMatch(it) }
        return entryId
    }

    /** The linking entry's id, and the ids whose match records no longer apply. */
    private suspend fun linkLocked(
        itemId: String,
        hardcoverId: Int,
        title: String,
        authors: List<String>,
        move: Boolean,
    ): Pair<String, Set<String>> {
        val book = dao.book(itemId)
        val wish = if (book == null) dao.wish(itemId) else null
        require(book != null || wish != null) { text(R.string.msg_book_gone) }
        require(dao.removedEntry(itemId) == null) { text(R.string.msg_library_restore_first) }
        val entry = wish ?: dao.allWishes().firstOrNull { it.linkedBookId == book!!.id }
        if (entry != null) {
            require(entry.linkedBookId?.let { dao.removedEntry(it) } == null) {
                text(R.string.msg_library_restore_first)
            }
            if (entry.hardcoverId == hardcoverId)
                return entry.id to setOfNotNull(entry.id, entry.linkedBookId)
        }
        val stored = dao.allStoredWishes()
        val holders = stored.filter { it.hardcoverId == hardcoverId && it.id != entry?.id }
        require(holders.size <= 1) { text(R.string.msg_discovery_duplicates) }
        val books = holders.mapNotNull { h -> h.linkedBookId?.let { dao.book(it) } }
        val holder =
            hardcoverHolder(
                itemId,
                hardcoverId,
                books.associateBy { it.id },
                stored,
                dao.allRemoved().map { it.id }.toSet(),
            )
        val touched = mutableSetOf<String>()
        if (holder != null) {
            if (!move) throw HardcoverLinkTaken(holder, takenMessage(holder))
            val from = holders.single()
            touched += setOfNotNull(from.id, holder.bookId)
            if (entry != null) {
                // This item keeps its own entry; the other one gives the Hardcover book up and
                // keeps everything else. A link to a book that no longer exists is cleared.
                dao.updateWish(
                    from.copy(
                        hardcoverId = null,
                        hardcoverTitle = "",
                        hardcoverSynced = false,
                        linkedBookId = holder.bookId,
                    )
                )
            } else if (holder.removed) dao.unmarkRemoved(from.id)
        }
        if (entry != null) {
            dao.updateWish(
                entry.copy(
                    hardcoverId = hardcoverId,
                    hardcoverTitle = title.trim().ifBlank { entry.title }.take(MAX_TITLE),
                    hardcoverSynced = false,
                )
            )
            return entry.id to setOfNotNull(entry.id, entry.linkedBookId) + touched
        }
        book!!
        val free = holders.singleOrNull()?.let { dao.wish(it.id) }
        if (free != null) {
            // Hardcover's book is already a catalogue entry: the file joins it, with its lists. A
            // link to a book that no longer exists, or a moved match, is replaced.
            dao.updateWish(free.copy(linkedBookId = book.id))
            dao.allWishMemberships()
                .filter { it.wishId == free.id }
                .forEach { dao.addMembership(Membership(book.id, it.shelfId)) }
            return free.id to setOf(free.id, book.id) + touched
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
            return id to setOf(id, book.id) + touched
        } catch (e: Exception) {
            folder.deleteRecursively()
            throw e
        }
    }

    private fun takenMessage(holder: HardcoverHolder) =
        repo.context.getString(
            if (holder.removed) R.string.hc_link_taken_removed else R.string.hc_link_taken,
            holder.title,
        )

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
