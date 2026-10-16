package dev.sadking.folio.core.sync

import dev.sadking.folio.core.BookPaths

/**
 * Whether the phone has changes an e-reader hasn't had yet. `GET /folio/v2/hello` answers with it,
 * and the plugin syncs when it is true. It is judged from the phone's books and that e-reader's
 * baselines only, since the e-reader's files are unknown until it syncs. A wrong "true" costs one
 * quiet sync; a wrong "false" waits for the next sync the e-reader starts on its own.
 */
object SyncPending {
    /**
     * True when a book in [phone] changed since [baselines] in a way the e-reader must follow: a
     * new book to send, a book removed or purged on the phone, a new file, a new title or author, a
     * new status, a new annotation, or a changed list. Books in [waiting] have a question open
     * about them; they don't count, or every poll would sync again just to ask it again.
     *
     * [root] is the library folder on the e-reader. When null, any folder holding the book's
     * `Author/Title.ext` counts as in place, so a library folder changed on the e-reader doesn't
     * make every book look renamed.
     */
    fun pending(
        phone: List<PhoneBook>,
        baselines: List<Baseline>,
        root: String? = null,
        waiting: Set<String> = emptySet(),
    ): Boolean {
        val onPhone = phone.map { it.id }.toSet()
        // A purged book the e-reader still had: it is deleted there.
        if (baselines.any { it.bookId !in onPhone && it.bookId !in waiting }) return true
        val byId = baselines.associateBy { it.bookId }
        val clean = root?.let(::cleanRoot)
        return phone.any { it.id !in waiting && changed(it, byId[it.id], clean) }
    }

    private fun changed(book: PhoneBook, b: Baseline?, root: String?): Boolean {
        // Removed on the phone: the e-reader deletes its copy, if it had one.
        if (book.removed) return b != null
        if (b == null) return book.hasFile && validSha(book.sha256) && kept(book.format)
        // The e-reader's copy refills a missing file at the next sync; no need to start one.
        if (!book.hasFile) return false
        if (book.sha256 != b.sha256) return true
        if (kept(book.format) && !inPlace(b.path, book, root)) return true
        val status = SyncStatus.fromPhone(book.status)
        // A blank baseline status means the sides didn't agree last time: a question or no record.
        val agreed = SyncStatus.normalize(b.status)
        if (status != null && agreed != null && status != agreed) return true
        if (book.annotations.any { it.id !in b.annotationIds }) return true
        return SyncPlanner.names(book.lists) != SyncPlanner.names(b.collections)
    }

    /**
     * Whether [path] is where the planner would leave [book]: `Author/Title.ext` from the phone's
     * metadata, ignoring case, or a numbered " (n)" version of it, under [root] or, when [root] is
     * null, under any folder.
     */
    internal fun inPlace(path: String, book: PhoneBook, root: String?): Boolean {
        val ext = book.format.lowercase()
        val wanted = BookPaths.key(BookPaths.path(book.authors, book.title, ext))
        val stem = Regex.escape(wanted.substringBeforeLast('.'))
        val folder = if (root == null) "(?:.*/)?" else Regex.escape(BookPaths.key(root) + "/")
        val pattern = folder + stem + """(?: \([1-9][0-9]*\))?\.""" + Regex.escape(ext)
        return Regex(pattern).matches(BookPaths.key(path))
    }
}
