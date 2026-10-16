package dev.sadking.folio.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import dev.sadking.folio.R
import dev.sadking.folio.core.BookFormats
import dev.sadking.folio.core.BookPaths
import dev.sadking.folio.core.Comic
import dev.sadking.folio.core.Epub
import dev.sadking.folio.core.FileTitle
import dev.sadking.folio.core.Metadata
import dev.sadking.folio.core.Pdf
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LibraryRepository(
    private val context: Context,
    val database: LibraryDatabase,
    /** The visible Books folder (M8); books moved there have their only copy in it. */
    val booksRoot: () -> File = { BooksFolder(context).location },
) {
    val dao = database.library()
    internal val lock = Mutex()
    val root = File(context.filesDir, "library").apply { mkdirs() }

    /** Whether Librillo may write to the Books folder: All files access is on. Tests replace it. */
    internal var folderAccess: () -> Boolean = { BooksFolder(context).hasAccess }

    /**
     * True when new books go into the Books folder rather than private storage: access is on and
     * the folder exists or can be made. Creates `Books/Inbox` the first time the folder is used.
     */
    fun folderReady(): Boolean {
        if (!folderAccess()) return false
        val books = booksRoot()
        File(books, BooksInbox.FOLDER).mkdirs()
        return books.isDirectory
    }

    /** True for a file in Librillo's private storage. */
    fun isPrivate(file: File): Boolean =
        file.canonicalPath.startsWith(root.canonicalPath + File.separator)

    /** True for a file anywhere in the Books folder, including Librillo's own `.librillo`. */
    fun inBooksFolder(file: File): Boolean =
        file.canonicalPath.startsWith(booksRoot().canonicalPath + File.separator)

    /**
     * True when [file] may be one of this book's files: in its private folder, or in the Books
     * folder. Paths come from the database and backups, so they are checked before use.
     */
    fun holds(bookId: String, file: File): Boolean =
        file.canonicalFile.parentFile == File(root, bookId).canonicalFile || inBooksFolder(file)

    /**
     * Adds an EPUB, PDF, CBZ or CBR file. The kind comes from the file's bytes, never from its name
     * alone. Returns the book and whether it is new; a file already in the library returns that
     * book (restored if it was removed). With folder access the book goes straight into the Books
     * folder at `Author/Title.<format>`; without it, into private storage. The source is left
     * alone.
     */
    suspend fun import(uri: Uri): Pair<Book, Boolean> =
        withContext(Dispatchers.IO) {
            val name = displayName(uri)
            lock.withLock {
                // The copy is made on the volume the book will live on, so filing it is a rename.
                val toFolder = folderReady()
                val stage =
                    if (toFolder)
                        File(booksRoot(), ".librillo/.import-${UUID.randomUUID()}.part").also {
                            it.parentFile!!.mkdirs()
                        }
                    else File(root, ".import-${UUID.randomUUID()}")
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(stage).use { output ->
                            Epub.copyBounded(input, output)
                            output.fd.sync()
                        }
                    } ?: error("Cannot open selected file")
                    addLocked(stage, name, toFolder)
                } finally {
                    stage.delete()
                }
            }
        }

    /**
     * Files a book dropped in `Books/Inbox`: moved (renamed, never copied) to
     * `Author/Title.<format>` and added. A file already in the library is left where it is and that
     * book is returned with false. Throws [NotABook], [UnreadableBook] or [TooLargeBook] for files
     * Librillo can't take; the file is then left where it is.
     */
    suspend fun fileFromInbox(file: File): Pair<Book, Boolean> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                require(
                    file.parentFile?.canonicalFile ==
                        File(booksRoot(), BooksInbox.FOLDER).canonicalFile
                ) {
                    "Not an Inbox file"
                }
                if (file.length() > Epub.MAX_FILE) throw TooLargeBook()
                addLocked(file, file.name, toFolder = true)
            }
        }

    /**
     * Adds [file] as a new book, or returns the book that already has its content (restored if it
     * was removed) and leaves [file] alone. A new book's file is renamed, never copied: to
     * `Author/Title.<format>` in the Books folder when [toFolder], else into private storage. The
     * rename and the record happen together; if recording fails, the file is renamed back. Call
     * under [lock].
     */
    internal suspend fun addLocked(
        file: File,
        name: String?,
        toFolder: Boolean,
    ): Pair<Book, Boolean> {
        val format =
            detectFormat(file, name)
                ?: throw NotABook(context.getString(R.string.import_unsupported_format))
        val hash = Epub.sha256(file)
        dao.duplicate(hash)?.let { book ->
            dao.removedEntry(book.id)?.let { removed -> dao.restoreRemoved(removed.groupId) }
            return book to false
        }
        val (m, coverBytes) =
            try {
                readMetadata(file, format, name)
            } catch (e: Exception) {
                throw UnreadableBook(e)
            }
        val id = UUID.randomUUID().toString()
        val dir = File(root, id)
        val books = booksRoot()
        val dest =
            if (toFolder)
                File(books, freePath(books, m.authors, m.title, mine = null, extension = format))
            else File(dir, "original.$format")
        return withContext(NonCancellable) {
            moveNoClobber(file, dest)
            try {
                val cover = coverBytes?.let { bytes ->
                    File(dir.apply { mkdirs() }, "cover-original")
                        .also { it.writeBytes(bytes) }
                        .absolutePath
                }
                val book =
                    Book(
                            id,
                            m.title,
                            encode(m.authors),
                            originalPath = dest.absolutePath,
                            currentPath = dest.absolutePath,
                            originalHash = hash,
                            currentHash = hash,
                            coverPath = cover,
                            format = format,
                        )
                        .withMetadata(m)
                dao.insert(book)
                book to true
            } catch (e: Exception) {
                runCatching { moveNoClobber(dest, file) }
                if (toFolder) removeEmptyFolder(dest)
                dir.deleteRecursively()
                throw e
            }
        }
    }

    /** Renames [from] to [to], making [to]'s folder; fails rather than replace a file. */
    private fun moveNoClobber(from: File, to: File) {
        to.parentFile!!.mkdirs()
        Files.move(from.toPath(), to.toPath())
    }

    /** The name the file provider shows for [uri], else the last part of its path. */
    private fun displayName(uri: Uri): String? =
        runCatching {
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
            .getOrNull() ?: uri.lastPathSegment

    /**
     * The book format of [file]: from its first bytes, then, for a ZIP they leave undecided, from
     * its entries. Null when it is not a book Librillo takes.
     */
    private fun detectFormat(file: File, name: String?): String? {
        val header = file.inputStream().use { it.readNBytes(BookFormats.HEADER_BYTES) }
        return BookFormats.detect(header, name) ?: Comic.zipKind(file)
    }

    /**
     * Metadata and cover for a new book. PDFs and CBRs fall back to the file name when their own
     * details can't be read; a cover is a bonus, never a reason to refuse a file.
     */
    private fun readMetadata(
        file: File,
        format: String,
        name: String?,
    ): Pair<Metadata, ByteArray?> =
        when (format) {
            BookFormats.EPUB -> Epub.parse(file).let { it.metadata to it.cover }
            BookFormats.PDF -> Pdf.metadata(file, name) to PdfCover.render(file)
            BookFormats.CBZ -> Comic.parse(file, name).let { it.metadata to it.cover }
            else ->
                RarComic.parse(file, name)?.let { it.metadata to it.cover }
                    ?: (Metadata(FileTitle.from(name)) to null)
        }

    /**
     * Adds a file found in the Books folder as a new book where it is: no copy and no rename. Null
     * when it isn't a book Librillo takes, or its content is already in the library. Call with
     * [lock] held.
     */
    internal suspend fun addInPlace(file: File, hash: String): Book? =
        withContext(Dispatchers.IO) {
            require(inBooksFolder(file) && file.isFile) { "Not a file in the Books folder" }
            if (dao.duplicate(hash) != null) return@withContext null
            val format = detectFormat(file, file.name) ?: return@withContext null
            val (m, coverBytes) =
                try {
                    readMetadata(file, format, file.name)
                } catch (_: Exception) {
                    return@withContext null
                }
            val id = UUID.randomUUID().toString()
            val dir = File(root, id)
            try {
                val cover = coverBytes?.let { bytes ->
                    File(dir.apply { mkdirs() }, "cover-original")
                        .also { it.writeBytes(bytes) }
                        .absolutePath
                }
                val book =
                    Book(
                            id,
                            m.title,
                            encode(m.authors),
                            originalPath = file.absolutePath,
                            currentPath = file.absolutePath,
                            originalHash = hash,
                            currentHash = hash,
                            coverPath = cover,
                            format = format,
                        )
                        .withMetadata(m)
                dao.insert(book)
                book
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }

    /**
     * Points a book at [file], where its content now is: the file was moved or renamed by hand.
     * Progress, notes and lists stay, since they belong to the book, not its path. An original kept
     * in the same file follows it. Null for a book that is gone or in Recently removed. Call with
     * [lock] held.
     */
    internal suspend fun relink(bookId: String, file: File): Book? =
        withContext(Dispatchers.IO) {
            require(inBooksFolder(file) && file.isFile) { "Not a file in the Books folder" }
            val book = dao.book(bookId) ?: return@withContext null
            if (dao.removedEntry(bookId) != null) return@withContext null
            val path = file.absolutePath
            val moved =
                book.copy(
                    currentPath = path,
                    originalPath =
                        if (book.originalPath == book.currentPath) path else book.originalPath,
                )
            dao.update(moved)
            moved
        }

    /**
     * Accepts new content, [hash], at a book's own [file] as a new revision: it was changed outside
     * Librillo. The phone's reading position is carried to the new revision, where it may land a
     * little off; older positions and bookmarks stay under the old one. When the book's original
     * was this same file, the original is the new content too, as the old bytes are gone. Null when
     * the file isn't a book of the same format. Call with [lock] held.
     */
    internal suspend fun acceptRevision(bookId: String, file: File, hash: String): Book? =
        withContext(Dispatchers.IO) {
            require(inBooksFolder(file) && file.isFile) { "Not a file in the Books folder" }
            val book = dao.book(bookId) ?: return@withContext null
            if (dao.removedEntry(bookId) != null || book.currentHash == hash)
                return@withContext null
            if (detectFormat(file, file.name) != book.format) return@withContext null
            val sameFile = book.originalPath == book.currentPath
            val revised =
                book.copy(
                    currentPath = file.absolutePath,
                    currentHash = hash,
                    originalPath = if (sameFile) file.absolutePath else book.originalPath,
                    originalHash = if (sameFile) hash else book.originalHash,
                )
            database.withTransaction {
                dao.update(revised)
                val old = dao.phoneReading(bookId, book.currentHash)
                if (old != null && dao.phoneReading(bookId, hash) == null)
                    dao.savePhoneReading(old.copy(revisionHash = hash))
            }
            revised
        }

    /**
     * Takes [file], new content for book [bookId] from an e-reader, as the book's new revision. The
     * old file is kept, never overwritten: in the Books folder it moves into `.librillo`, to
     * `originals` when it was the book's original or else to `previous`, and the new file takes its
     * name; in private storage the new file goes beside it. A book whose file is missing gets
     * [file] as its file again. The book may be in Recently removed. [file] is moved, not copied.
     * [name] is the file's name on the e-reader, a hint for its format. Returns the book, unchanged
     * when it already has this content. Null when [file] is not a book of the book's format, or the
     * book is gone. Call with [lock] held.
     */
    internal suspend fun reviseLocked(bookId: String, file: File, name: String): Book? =
        withContext(Dispatchers.IO) {
            val book = dao.book(bookId) ?: return@withContext null
            if (!file.isFile || file.length() > Epub.MAX_FILE) return@withContext null
            if (detectFormat(file, name) != book.format) return@withContext null
            val hash = Epub.sha256(file)
            val current = File(book.currentPath)
            val present = book.currentPath.isNotBlank() && current.isFile
            if (present && hash == book.currentHash) return@withContext book
            val books = booksRoot()
            val wasOriginal = book.originalPath == book.currentPath
            // Where the old file steps aside to, when it is in the Books folder.
            val kept =
                if (!present || !inBooksFolder(current)) null
                else
                    File(books, ".librillo/originals/${book.id}.${book.format}").takeIf {
                        wasOriginal && !it.exists()
                    }
                        ?: File(
                            books,
                            ".librillo/previous/${book.id}-${System.currentTimeMillis()}.${book.format}",
                        )
            val dest =
                when {
                    kept != null -> current
                    !present && folderReady() ->
                        File(
                            books,
                            freePath(
                                books,
                                decode(book.authors),
                                book.title,
                                mine = null,
                                extension = book.format,
                            ),
                        )
                    else -> File(File(root, book.id), "${UUID.randomUUID()}.${book.format}")
                }
            withContext(NonCancellable) {
                if (kept != null) moveNoClobber(current, kept)
                try {
                    moveNoClobber(file, dest)
                } catch (e: Exception) {
                    if (kept != null) moveNoClobber(kept, current)
                    throw e
                }
                val revised =
                    book.copy(
                        currentPath = dest.absolutePath,
                        currentHash = hash,
                        originalPath =
                            if (kept != null && wasOriginal) kept.absolutePath
                            else book.originalPath,
                    )
                try {
                    database.withTransaction {
                        dao.update(revised)
                        val old = dao.phoneReading(bookId, book.currentHash)
                        if (old != null && dao.phoneReading(bookId, hash) == null)
                            dao.savePhoneReading(old.copy(revisionHash = hash))
                    }
                } catch (e: Exception) {
                    // The record didn't change, so the files go back to where it says they are.
                    runCatching { moveNoClobber(dest, file) }
                    if (kept != null) runCatching { moveNoClobber(kept, current) }
                    throw e
                }
                revised
            }
        }

    /**
     * Moves books and wishes to Recently removed. Returns how many were removed, whether their
     * private files were cleaned, and the removed groups, so the removal can be undone.
     */
    suspend fun removeLocal(ids: Set<String>): Triple<Int, Boolean, List<String>> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                database.withTransaction {
                    val books = dao.allBooks()
                    val wishes = dao.allWishes()
                    val plan = localRemovalPlan(ids, books, wishes)
                    dao.unfinishedJob()?.let { job ->
                        require(
                            dao.tasks(job.id).none {
                                it.bookId in plan.bookIds && it.state != "COMPLETE"
                            }
                        ) {
                            "Cancel or finish the pending Kindle sync before removing its queued books"
                        }
                    }
                    val now = System.currentTimeMillis()
                    val groups = mutableSetOf<String>()
                    books
                        .filter { it.id in plan.bookIds }
                        .forEach {
                            dao.markRemoved(RemovedEntry(it.id, it.id, it.title, "EPUB", now))
                            groups += it.id
                        }
                    wishes
                        .filter { it.id in plan.wishIds }
                        .forEach {
                            val group =
                                it.linkedBookId?.takeIf { id -> id in plan.bookIds } ?: it.id
                            dao.markRemoved(RemovedEntry(it.id, group, it.title, "Catalogue", now))
                            groups += group
                        }
                    Triple(plan.visibleCount, true, groups.toList())
                }
            }
        }

    suspend fun restoreRemoved(group: String) = lock.withLock {
        database.withTransaction { dao.restoreRemoved(group) }
    }

    suspend fun purgeRemoved(group: String): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val entries = dao.allRemoved().filter { it.groupId == group }
                // A book in the Books folder has its only copy there; purging is the one place
                // Librillo deletes it, after the confirmation that says so.
                val folderFiles =
                    entries
                        .filter { it.kind == "EPUB" }
                        .mapNotNull { dao.book(it.id) }
                        .flatMap { listOf(File(it.currentPath), File(it.originalPath)) }
                        .filter { inBooksFolder(it) }
                        .distinctBy { it.canonicalPath }
                val folders = entries.map { entry ->
                    UUID.fromString(entry.id)
                    File(root, entry.id).also {
                        require(it.canonicalFile == File(root.canonicalFile, entry.id))
                    }
                }
                database.withTransaction {
                    entries.forEach { entry ->
                        if (entry.kind == "EPUB") {
                            dao.deleteLocalAnnotations(entry.id)
                            dao.deleteLocalReading(entry.id)
                            dao.deleteLocalTransfers(entry.id)
                            dao.deleteLocalJournal(entry.id)
                            dao.deleteLocalBook(entry.id)
                        } else dao.deleteWish(entry.id)
                    }
                    dao.restoreRemoved(group)
                }
                val filesGone =
                    folderFiles
                        .map { file ->
                            (!file.exists() || file.delete()).also { removeEmptyFolder(file) }
                        }
                        .all { it }
                folders
                    .map {
                        !it.exists() || runCatching { it.deleteRecursively() }.getOrDefault(false)
                    }
                    .all { it } && filesGone
            }
        }

    suspend fun detach(bookId: String) = lock.withLock {
        database.withTransaction {
            require(dao.removedEntry(bookId) == null) { "Restore this book first" }
            dao.allWishes()
                .filter { it.linkedBookId == bookId }
                .forEach { wish ->
                    dao.allMemberships()
                        .filter { it.bookId == bookId }
                        .forEach { dao.addWishMembership(WishMembership(wish.id, it.shelfId)) }
                    dao.updateWish(wish.copy(linkedBookId = null))
                }
        }
    }

    /**
     * Adds books to a manual list or takes them out, and/or sets their status. A status that
     * changes records [statusAt] as the time it changed: now, or the time the change was made on an
     * e-reader.
     */
    suspend fun manageBatch(
        ids: Set<String>,
        shelfId: String? = null,
        add: Boolean = true,
        status: String? = null,
        statusAt: Long = System.currentTimeMillis(),
    ) = lock.withLock {
        database.withTransaction {
            if (shelfId != null)
                require(dao.allShelves().any { it.id == shelfId && it.ruleField == "Manual" }) {
                    "Choose a manual list"
                }
            if (status != null) require(status in phoneStatuses)
            val linked =
                dao.allWishes().filter { it.id in ids }.mapNotNull { it.linkedBookId }.toSet()
            val owned = dao.allBooks().filter { it.id in ids || it.id in linked }
            val wishes =
                dao.allWishes().filter {
                    it.id in ids || it.linkedBookId in owned.map { b -> b.id }
                }
            owned.forEach { book ->
                if (status != null && status != book.status)
                    dao.update(book.copy(status = status, statusAt = statusAt))
                if (shelfId != null) {
                    if (add) dao.addMembership(Membership(book.id, shelfId))
                    else dao.removeMembership(book.id, shelfId)
                }
            }
            wishes.forEach { wish ->
                if (status != null) dao.updateWish(wish.copy(status = status))
                if (shelfId != null) {
                    if (add) dao.addWishMembership(WishMembership(wish.id, shelfId))
                    else dao.removeWishMembership(wish.id, shelfId)
                }
            }
        }
    }

    suspend fun saveCatalogue(id: String?, metadata: Metadata, cover: Uri?): WishBook =
        withContext(Dispatchers.IO) {
            lock.withLock {
                require(
                    metadata.title.isNotBlank() &&
                        metadata.title.length <= 2000 &&
                        metadata.authors.size <= 100 &&
                        metadata.authors.all { it.length <= 1000 }
                ) {
                    "Enter a title and valid authors"
                }
                val key = id ?: UUID.randomUUID().toString()
                require(dao.removedEntry(key) == null) { "Restore this book first" }
                val old =
                    id?.let { dao.wish(it) ?: error("Book no longer exists") }
                        ?: WishBook(key, "manual:$key", metadata.title, encode(metadata.authors))
                var coverPath = old.coverPath
                var stagedCover: File? = null
                try {
                    if (cover != null) {
                        val bytes = java.io.ByteArrayOutputStream()
                        context.contentResolver.openInputStream(cover)?.use {
                            Epub.copyBounded(it, bytes, 10L * 1024 * 1024)
                        } ?: error("Cannot open cover")
                        val data = bytes.toByteArray()
                        val bounds =
                            android.graphics.BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                        require(
                            bounds.outWidth in 1..12000 &&
                                bounds.outHeight in 1..12000 &&
                                bounds.outWidth.toLong() * bounds.outHeight <= 40000000
                        ) {
                            "Cover is invalid or too large"
                        }
                        val folder = File(root, key).apply { mkdirs() }
                        stagedCover =
                            File(folder, "cover-${UUID.randomUUID()}").also { it.writeBytes(data) }
                        coverPath = stagedCover!!.absolutePath
                    }
                    val value =
                        old.copy(
                            title = metadata.title.trim(),
                            authors = encode(metadata.authors),
                            details = metadataJson(metadata).toString(),
                            coverPath = coverPath,
                        )
                    if (id == null) require(dao.insertWish(value) != -1L) else dao.updateWish(value)
                    value
                } catch (e: Exception) {
                    stagedCover?.delete()
                    throw e
                }
            }
        }

    suspend fun attach(wishId: String, bookId: String): Book =
        withContext(Dispatchers.IO) {
            lock.withLock {
                database.withTransaction {
                    require(dao.removedEntry(wishId) == null && dao.removedEntry(bookId) == null) {
                        "Restore removed books before attaching"
                    }
                    val wish = dao.wish(wishId) ?: error("Catalogue book no longer exists")
                    val book = dao.book(bookId) ?: error("EPUB no longer exists")
                    requireAttachment(wish, book, dao.allWishes())
                    dao.updateWish(wish.copy(linkedBookId = book.id))
                    dao.allWishMemberships()
                        .filter { it.wishId == wish.id }
                        .forEach { dao.addMembership(Membership(book.id, it.shelfId)) }
                    book
                }
            }
        }

    suspend fun edit(id: String, metadata: Metadata, coverUri: Uri?): Book =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val old = dao.book(id) ?: error("Book no longer exists")
                if (old.format != BookFormats.EPUB)
                    return@withLock editRecord(old, metadata, coverUri)
                if (inBooksFolder(File(old.currentPath)))
                    return@withLock editInFolder(old, metadata, coverUri)
                val dir = File(root, id)
                val revision = UUID.randomUUID().toString()
                val temp = File(dir, ".$revision.part")
                val dest = File(dir, "$revision.epub")
                val bytes = coverUri?.let { uri ->
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        java.io
                            .ByteArrayOutputStream()
                            .also { Epub.copyBounded(input, it, 10L * 1024 * 1024) }
                            .toByteArray()
                    } ?: error("Cannot open cover")
                }
                val mime = coverUri?.let { context.contentResolver.getType(it) } ?: "image/jpeg"
                if (bytes != null) {
                    val options =
                        android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    require(
                        options.outWidth in 1..12000 &&
                            options.outHeight in 1..12000 &&
                            options.outWidth.toLong() * options.outHeight <= 40_000_000
                    ) {
                        "Cover is not a supported image or is too large"
                    }
                }
                try {
                    Epub.edit(File(old.currentPath), temp, metadata, bytes, mime)
                    check(temp.renameTo(dest)) { "Cannot commit EPUB revision" }
                    val cover =
                        bytes?.let {
                            File(dir, "cover-$revision").also { it.writeBytes(bytes) }.absolutePath
                        } ?: old.coverPath
                    val book =
                        old.withMetadata(metadata)
                            .copy(
                                currentPath = dest.absolutePath,
                                currentHash = Epub.sha256(dest),
                                coverPath = cover,
                            )
                    dao.update(book)
                    book
                } finally {
                    temp.delete()
                }
            }
        }

    /**
     * An edit to a book in the Books folder: the new file is written next to the old one, the old
     * one is kept in `.librillo` (as the original if it was, else as a previous version), and the
     * new one takes the book's path, which follows a new title or author. Nothing is written if the
     * file changed outside Librillo.
     */
    private suspend fun editInFolder(old: Book, metadata: Metadata, coverUri: Uri?): Book {
        val books = booksRoot()
        val current = File(old.currentPath)
        require(current.isFile && Epub.sha256(current) == old.currentHash) {
            "This book's file changed outside Librillo. Open the Books folder to check it."
        }
        val (bytes, mime) = coverBytes(coverUri)
        val revision = UUID.randomUUID().toString()
        val temp = File(current.parentFile, ".$revision.part")
        val kept =
            if (old.originalPath == old.currentPath)
                File(books, ".librillo/originals/${old.id}.epub")
            else File(books, ".librillo/previous/${old.id}-${System.currentTimeMillis()}.epub")
        try {
            Epub.edit(current, temp, metadata, bytes, mime)
            val path = freePath(books, metadata.authors, metadata.title, mine = current)
            val dest = File(books, path)
            kept.parentFile!!.mkdirs()
            check(!kept.exists() && current.renameTo(kept)) { "Cannot keep the previous file" }
            dest.parentFile!!.mkdirs()
            if (!temp.renameTo(dest)) {
                kept.renameTo(current)
                error("Cannot commit EPUB revision")
            }
            if (dest.parentFile != current.parentFile) removeEmptyFolder(current)
            val cover =
                bytes?.let {
                    File(File(root, old.id).apply { mkdirs() }, "cover-$revision")
                        .also { it.writeBytes(bytes) }
                        .absolutePath
                } ?: old.coverPath
            val book =
                old.withMetadata(metadata)
                    .copy(
                        currentPath = dest.absolutePath,
                        currentHash = Epub.sha256(dest),
                        originalPath =
                            if (old.originalPath == old.currentPath) kept.absolutePath
                            else old.originalPath,
                        coverPath = cover,
                    )
            dao.update(book)
            return book
        } finally {
            temp.delete()
        }
    }

    /**
     * An edit to a PDF, CBZ or CBR: Librillo never rewrites these files, so the new details live in
     * the library only. A file in the Books folder is renamed to follow a new title or author; its
     * content stays the same.
     */
    private suspend fun editRecord(old: Book, metadata: Metadata, coverUri: Uri?): Book {
        require(metadata.title.isNotBlank()) { context.getString(R.string.edit_title_required) }
        require(
            metadata.seriesIndex.isBlank() ||
                metadata.seriesIndex.toDoubleOrNull()?.let { it >= 0 && it.isFinite() } == true
        ) {
            context.getString(R.string.edit_series_number_invalid)
        }
        val (bytes, _) = coverBytes(coverUri)
        val current = File(old.currentPath)
        var path = old.currentPath
        var renamed: File? = null
        if (inBooksFolder(current)) {
            val books = booksRoot()
            require(current.isFile && Epub.sha256(current) == old.currentHash) {
                "This book's file changed outside Librillo. Open the Books folder to check it."
            }
            val dest =
                File(
                    books,
                    freePath(
                        books,
                        metadata.authors,
                        metadata.title,
                        mine = current,
                        extension = old.format,
                    ),
                )
            if (dest.canonicalFile != current.canonicalFile) {
                dest.parentFile!!.mkdirs()
                check(!dest.exists() && current.renameTo(dest)) { "Cannot rename the book's file" }
                renamed = dest
                path = dest.absolutePath
            }
        }
        try {
            val cover =
                bytes?.let {
                    File(File(root, old.id).apply { mkdirs() }, "cover-${UUID.randomUUID()}")
                        .also { it.writeBytes(bytes) }
                        .absolutePath
                } ?: old.coverPath
            val book =
                old.withMetadata(metadata)
                    .copy(
                        currentPath = path,
                        originalPath =
                            if (old.originalPath == old.currentPath) path else old.originalPath,
                        coverPath = cover,
                    )
            dao.update(book)
            if (renamed != null && renamed.parentFile != current.parentFile)
                removeEmptyFolder(current)
            return book
        } catch (e: Exception) {
            // The record didn't change, so the file goes back to the name it has there.
            if (renamed != null) renamed.renameTo(current)
            throw e
        }
    }

    private fun coverBytes(coverUri: Uri?): Pair<ByteArray?, String> {
        val bytes = coverUri?.let { uri ->
            context.contentResolver.openInputStream(uri)?.use { input ->
                java.io
                    .ByteArrayOutputStream()
                    .also { Epub.copyBounded(input, it, 10L * 1024 * 1024) }
                    .toByteArray()
            } ?: error("Cannot open cover")
        }
        if (bytes != null) {
            val options =
                android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            require(
                options.outWidth in 1..12000 &&
                    options.outHeight in 1..12000 &&
                    options.outWidth.toLong() * options.outHeight <= 40_000_000
            ) {
                "Cover is not a supported image or is too large"
            }
        }
        val mime = coverUri?.let { context.contentResolver.getType(it) } ?: "image/jpeg"
        return bytes to mime
    }

    /**
     * A free `Author/Title.<extension>` path in [books]. A name counts as taken when a file with
     * the same name, ignoring case, is there, unless it is [mine].
     */
    internal fun freePath(
        books: File,
        authors: List<String>,
        title: String,
        mine: File?,
        extension: String = BookFormats.EPUB,
    ): String =
        BookPaths.path(authors, title, extension = extension) { candidate ->
            val file = File(books, candidate)
            val key = BookPaths.key(file.name)
            file.parentFile?.listFiles().orEmpty().any {
                BookPaths.key(it.name) == key &&
                    (mine == null || it.canonicalFile != mine.canonicalFile)
            }
        }

    /** Removes an author folder left empty, never the Books folder itself. */
    internal fun removeEmptyFolder(file: File) {
        val parent = file.parentFile ?: return
        if (
            inBooksFolder(parent) &&
                !parent.name.startsWith('.') &&
                parent.list()?.isEmpty() == true
        )
            parent.delete()
    }
}
