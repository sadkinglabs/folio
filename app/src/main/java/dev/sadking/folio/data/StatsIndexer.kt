package dev.sadking.folio.data

import dev.sadking.folio.core.stats.EpubWords
import dev.sadking.folio.reader.koreaderDigest
import java.io.File
import kotlinx.coroutines.CancellationException

/**
 * Fills in what the statistics need from each book's file (a6): an EPUB's word count, and the
 * KOReader digest of each file revision, so a Kindle session can find its book. A file is read only
 * when its revision has no row yet. Nothing is deleted, and no book is changed.
 */
class StatsIndexer(
    private val library: LibraryDao,
    private val stats: StatsDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Indexes every book in the library and returns how many gained a row. */
    suspend fun run(): Int {
        val words = stats.allWords().associateBy { it.bookId }.toMutableMap()
        var changed = 0
        for (book in library.allBooks()) {
            try {
                val wrote = indexWords(book, words)
                val digested = indexDigests(book)
                if (wrote || digested) changed++
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                // One bad file never stops the run. The book is tried again on the next run.
            }
        }
        return changed
    }

    /** Counts the words of an EPUB's current revision. A null count is kept, not retried. */
    private suspend fun indexWords(book: Book, words: MutableMap<String, BookWordsRow>): Boolean {
        if (book.format != "epub") return false
        if (words[book.id]?.revisionHash == book.currentHash) return false
        val file = File(book.currentPath)
        if (!file.isFile) return false
        val row = BookWordsRow(book.id, book.currentHash, EpubWords.count(file), now())
        stats.upsertWords(row)
        words[book.id] = row
        return true
    }

    /** Stores the KOReader digest of the original and the current file, for each new revision. */
    private suspend fun indexDigests(book: Book): Boolean {
        var changed = false
        val revisions =
            listOf(book.originalPath to book.originalHash, book.currentPath to book.currentHash)
        for ((path, hash) in revisions) {
            val file = File(path)
            if (!file.isFile) continue
            // Read again each time: the original and current file may be the same revision.
            if (stats.digestsOf(book.id).any { it.revisionHash == hash }) continue
            val row = KoreaderDigestRow(koreaderDigest(file), book.id, hash, now())
            stats.upsertDigests(listOf(row))
            changed = true
        }
        return changed
    }
}
