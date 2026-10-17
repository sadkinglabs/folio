package dev.sadking.folio.core.sync

import dev.sadking.folio.core.BookPaths
import dev.sadking.folio.core.FileTitle
import java.security.MessageDigest

/**
 * The 3-way compare between the e-reader, the phone and what both agreed at the last sync (protocol
 * v2, M9 step 3). Pure: it reads lists and returns a plan. docs/SYNC_PROTOCOL.md describes every
 * rule in plain English.
 */
object SyncPlanner {
    const val DEFAULT_ROOT = "Librillo"

    const val STATUS = "status"
    const val FILE = "file"
    const val DELETE_VS_EDIT = "delete-vs-edit"

    /**
     * Plans one sync. [home] is KOReader's home folder, for information only; every path is
     * relative to it. [root] is the library folder inside it. [answers] maps question ids to the
     * option the owner chose.
     *
     * [ignored] holds the SHA-256 of e-reader files the phone refused before (not a book it can
     * read, or too large). A file with that content is never uploaded again, so it is not imported,
     * not taken as a new revision and not tidied into the library folder: it is left where it is
     * until its content changes (rule 13).
     */
    fun plan(
        @Suppress("UNUSED_PARAMETER") home: String,
        root: String = DEFAULT_ROOT,
        kindle: List<KindleFile>,
        phone: List<PhoneBook>,
        baselines: List<Baseline>,
        answers: Map<String, String> = emptyMap(),
        ignored: Set<String> = emptySet(),
    ): SyncPlan {
        checkInput(kindle, phone)
        return Planner(cleanRoot(root), kindle, phone, baselines, answers, ignored).run()
    }

    /**
     * The baselines to record once a sync is over, from both sides as they now are: [kindle] as
     * [kindleAfter] gives it and [phone] read again after the phone applied its actions. The list
     * replaces all of [previous]. Each book whose file both sides hold gets a fresh row; a field
     * the two sides still disagree on keeps its old value, so the difference is still seen as a
     * change next time. An old row stays while either side still has the book, and is dropped once
     * both have deleted it.
     */
    fun baselines(
        kindle: List<KindleFile>,
        phone: List<PhoneBook>,
        previous: List<Baseline>,
        root: String = DEFAULT_ROOT,
    ): List<Baseline> {
        checkInput(kindle, phone)
        val clean = cleanRoot(root)
        val books = phone.associateBy { it.id }
        val old = previous.sortedBy { it.bookId }.associateBy { it.bookId }
        val matching = match(clean, kindle.filter { kept(it.format) }, phone, previous)
        val rows = LinkedHashMap<String, Baseline>()
        for ((bookId, file) in matching.pairs) {
            val book = books.getValue(bookId)
            if (book.removed || !book.hasFile || book.sha256 != file.sha256) continue
            val before = old[bookId]
            val phoneStatus = SyncStatus.fromPhone(book.status)
            val kindleStatus = SyncStatus.normalize(file.status) ?: SyncStatus.UNREAD
            val agreed = phoneStatus != null && phoneStatus == kindleStatus
            val live = book.annotations.filter { !it.deleted }.associateBy { it.id }
            val onKindle = file.annotations.associateBy { it.id }
            val knew = before?.annotationIds.orEmpty()
            // Both have it; or the baseline had it and only one side still does, so the
            // difference still counts as a change (a deletion not yet carried over) next time.
            val annotationIds =
                (live.keys + onKindle.keys + knew)
                    .filter { id ->
                        if ((id in live) == (id in onKindle)) id in live else id in knew
                    }
                    .toSet()
            val lists = names(book.lists)
            val collections = names(file.collections)
            val was = before?.collections.orEmpty()
            rows[bookId] =
                Baseline(
                    bookId = bookId,
                    path = file.path,
                    sha256 = file.sha256,
                    status = if (agreed) kindleStatus else before?.status.orEmpty(),
                    statusAt = if (agreed) book.statusAt else before?.statusAt ?: 0,
                    annotationIds = annotationIds,
                    annotationStates =
                        if (!file.annotationsComplete)
                            before?.annotationStates?.filterKeys { it in annotationIds }
                        else
                            annotationIds
                                .mapNotNull { id ->
                                    val p = live[id]
                                    val k = onKindle[id]
                                    val agreed =
                                        if (p == null || k == null) null
                                        else
                                            AnnotationMerge.state(p).takeIf {
                                                it == AnnotationMerge.state(k)
                                            }
                                    (agreed ?: before?.annotationStates?.get(id))?.let { id to it }
                                }
                                .toMap(),
                    collections =
                        (lists + collections + was)
                            .filter { name ->
                                if ((name in lists) == (name in collections)) name in lists
                                else name in was
                            }
                            .toSet(),
                )
        }
        val held = matching.pairs.keys + matching.purged.values.map { it.bookId }
        for (b in old.values) {
            if (b.bookId in rows) continue
            val book = books[b.bookId]
            if ((book != null && !book.removed) || b.bookId in held) rows[b.bookId] = b
        }
        return rows.values.sortedBy { it.bookId }
    }

    /**
     * The e-reader's files after it ran [actions], counting only those [results] report as done. A
     * download takes its title, authors and format from [phone].
     */
    fun kindleAfter(
        kindle: List<KindleFile>,
        actions: List<KindleAction>,
        phone: List<PhoneBook>,
        results: List<KindleResult>,
    ): List<KindleFile> {
        val done = results.filter { it.ok }.associateBy { it.index }
        val books = phone.associateBy { it.id }
        val files = LinkedHashMap(kindle.associateBy { it.path })
        // Where a file really went, when the e-reader picked another name than planned.
        val moved = HashMap<String, String>()
        fun at(path: String) = moved[path] ?: path
        actions.forEachIndexed { index, action ->
            val result = done[index] ?: return@forEachIndexed
            when (action) {
                is KindleAction.Download -> {
                    val to = result.path ?: at(action.to)
                    moved[action.to] = to
                    val old = if (action.replaces != null) files.remove(at(action.to)) else null
                    val book = books[action.bookId]
                    files[to] =
                        old?.copy(path = to, size = action.size, sha256 = action.sha256)
                            ?: KindleFile(
                                path = to,
                                size = action.size,
                                sha256 = action.sha256,
                                format = book?.format ?: to.substringAfterLast('.'),
                                title = book?.title,
                                authors = book?.authors.orEmpty(),
                                // A new book's notes are the ones just sent, when the plugin
                                // takes edits.
                                annotationsComplete = kindle.any { it.annotationsComplete },
                            )
                }
                is KindleAction.Move -> {
                    val file = files.remove(at(action.from)) ?: return@forEachIndexed
                    val to = result.path ?: action.to
                    moved[action.to] = to
                    files[to] = file.copy(path = to)
                }
                is KindleAction.Delete -> files.remove(at(action.path))
                is KindleAction.Upload -> Unit
                is KindleAction.SetStatus ->
                    files.computeIfPresent(at(action.path)) { _, f ->
                        f.copy(status = action.status)
                    }
                is KindleAction.AddAnnotations ->
                    files.computeIfPresent(at(action.path)) { _, f ->
                        val have = f.annotations.map { it.id }.toSet()
                        f.copy(
                            annotations =
                                f.annotations + action.annotations.filter { it.id !in have }
                        )
                    }
                is KindleAction.EditAnnotations ->
                    files.computeIfPresent(at(action.path)) { _, f ->
                        val edits = action.annotations.associateBy { it.id }
                        f.copy(
                            annotations =
                                f.annotations.map { a ->
                                    edits[a.id]?.let {
                                        a.copy(
                                            note = it.note,
                                            color = it.color,
                                            drawer = it.drawer,
                                            updated = it.updated,
                                            kind = it.kind,
                                        )
                                    } ?: a
                                }
                        )
                    }
                is KindleAction.DeleteAnnotations ->
                    files.computeIfPresent(at(action.path)) { _, f ->
                        val gone = action.ids.toSet()
                        f.copy(annotations = f.annotations.filter { it.id !in gone })
                    }
                is KindleAction.SetCollections ->
                    files.computeIfPresent(at(action.path)) { _, f ->
                        f.copy(collections = action.collections)
                    }
            }
        }
        return files.values.sortedBy { it.path }
    }

    /**
     * The phone actions to apply after the e-reader reported [results]: an import or revision only
     * when its upload succeeded.
     */
    fun phoneActionsAfter(plan: SyncPlan, results: List<KindleResult>): List<PhoneAction> {
        val done = results.filter { it.ok }.map { it.index }.toSet()
        val uploaded =
            plan.kindle
                .withIndex()
                .filter { (i, a) -> a is KindleAction.Upload && i in done }
                .map { (it.value as KindleAction.Upload).path }
                .toSet()
        return plan.phone.filter { action ->
            when (action) {
                is PhoneAction.Import -> action.path in uploaded
                is PhoneAction.Revise -> action.path in uploaded
                else -> true
            }
        }
    }

    /** A question's id: the same for the same book and kind at every sync. */
    fun questionId(kind: String, bookId: String?, path: String?): String {
        val subject = bookId ?: "path:" + BookPaths.key(path.orEmpty())
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest("$kind\n$subject".toByteArray(Charsets.UTF_8))
        return "q-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** List or collection names, trimmed, without blanks or repeats. */
    internal fun names(names: Collection<String>): Set<String> =
        names.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet()
}

/** One run of [SyncPlanner.plan]. */
private class Planner(
    val root: String,
    kindleIn: List<KindleFile>,
    phoneIn: List<PhoneBook>,
    val baselineIn: List<Baseline>,
    val answers: Map<String, String>,
    /** Content the phone refused before: never uploaded again (rule 13). */
    val ignored: Set<String>,
) {
    val kindle = kindleIn.filter { kept(it.format) }
    val phone = phoneIn
    val baseline = baselineIn.sortedBy { it.bookId }.associateBy { it.bookId }

    /** Every path the e-reader uses or will use, as [BookPaths.key]. */
    val occupied = kindleIn.map { BookPaths.key(it.path) }.toMutableSet()

    val kindleActions = mutableListOf<KindleAction>()
    val phoneActions = mutableListOf<PhoneAction>()
    val questions = mutableListOf<Question>()
    val matched = sortedMapOf<String, String>()

    fun run(): SyncPlan {
        val matching = match(root, kindle, phone, baselineIn)
        val books = phone.associateBy { it.id }
        val pairs = matching.pairs.entries.sortedBy { it.value.path }
        for ((bookId, file) in pairs) {
            val book = books.getValue(bookId)
            matched[file.path] = bookId
            if (book.removed) removedOnPhone(book, file) else both(book, file)
        }
        for (book in phone.sortedBy { it.id }) {
            if (book.id in matching.pairs || book.removed || !book.hasFile) continue
            if (!validSha(book.sha256) || !kept(book.format)) continue
            onlyOnPhone(book)
        }
        for ((path, b) in matching.purged.entries.sortedBy { it.key }) {
            purgedOnPhone(kindle.first { it.path == path }, b)
        }
        val removedByContent = phone.filter { it.removed }.sortedBy { it.id }.groupBy { it.sha256 }
        val activeContent = phone.filter { !it.removed }.map { it.sha256 }.toSet()
        for (file in matching.loose) {
            when {
                // Another copy of a book the phone has: left where it is.
                file.sha256 in activeContent -> Unit
                file.sha256 in removedByContent ->
                    deleteFromKindle(file, removedByContent.getValue(file.sha256).first())
                else -> importNew(file)
            }
        }
        return SyncPlan(
            kindle = kindleActions.sortedWith(kindleOrder),
            phone = phoneActions.sortedWith(phoneOrder),
            questions =
                questions.sortedWith(compareBy({ it.path.orEmpty() }, { it.bookId }, { it.kind })),
            matched = matched,
        )
    }

    // ---- Rules 4 and 3: books removed or purged on the phone ----

    /** Rule 4: a book in Recently removed still on the e-reader. */
    fun removedOnPhone(book: PhoneBook, file: KindleFile) {
        val b = baseline[book.id]
        if (b == null || file.sha256 == b.sha256) return deleteFromKindle(file, book)
        val q =
            question(
                REMOVED_SIDES,
                book.id,
                file.path,
                "${book.title} was removed on the phone but changed on the Kindle. " +
                    "Delete it from the Kindle, or keep it?",
            )
        when (answer(q)) {
            Side.PHONE -> deleteFromKindle(file, book)
            Side.KINDLE -> {
                phoneActions += PhoneAction.Restore(book.id)
                if (file.sha256 !in ignored) {
                    kindleActions += KindleAction.Upload(file.path)
                    phoneActions += PhoneAction.Revise(book.id, file.path)
                }
            }
            null -> Unit
        }
    }

    /** Rule 3: a file whose book was purged from the phone. */
    fun purgedOnPhone(file: KindleFile, b: Baseline) {
        val changed = file.sha256 != b.sha256 || file.annotations.any { it.id !in b.annotationIds }
        if (!changed) {
            kindleActions += KindleAction.Delete(file.path)
            return
        }
        // The phone can't take this content, so "Keep it" could not import it: leave it alone.
        if (file.sha256 in ignored) return
        val q =
            question(
                REMOVED_SIDES,
                b.bookId,
                file.path,
                "${title(file)} was deleted from Librillo but changed on the Kindle. " +
                    "Delete it from the Kindle, or keep it?",
            )
        when (answer(q)) {
            Side.PHONE -> kindleActions += KindleAction.Delete(file.path)
            Side.KINDLE -> importNew(file)
            null -> Unit
        }
    }

    /** Deletes [file] from the e-reader, keeping its annotations on [book] first. */
    fun deleteFromKindle(file: KindleFile, book: PhoneBook) {
        val missing = lacking(book.annotations, file.annotations)
        if (missing.isNotEmpty()) phoneActions += PhoneAction.AddAnnotations(book.id, missing)
        kindleActions += KindleAction.Delete(file.path)
    }

    // ---- Rule 2: books only on the phone ----

    fun onlyOnPhone(book: PhoneBook) {
        val b = baseline[book.id] ?: return download(book)
        if (book.sha256 == b.sha256) {
            phoneActions += PhoneAction.Remove(book.id)
            return
        }
        val q =
            question(
                DELETED_SIDES,
                book.id,
                b.path,
                "${book.title} was deleted on the Kindle but changed on the phone. " +
                    "Remove it from the phone, or send it back?",
            )
        when (answer(q)) {
            Side.KINDLE -> phoneActions += PhoneAction.Remove(book.id)
            Side.PHONE -> download(book)
            null -> Unit
        }
    }

    /** Sends a book the e-reader doesn't have, with its status, annotations and lists. */
    fun download(book: PhoneBook) {
        val to = allocate(book.authors, book.title, book.format)
        kindleActions += KindleAction.Download(book.id, book.sha256, book.size, to)
        val fresh =
            KindleFile(
                to,
                book.size,
                book.sha256,
                book.format,
                title = book.title,
                authors = book.authors,
            )
        metadata(book, fresh, to, null)
    }

    // ---- Rule 3: files only on the e-reader ----

    fun importNew(file: KindleFile) {
        // Refused before: not sent again, and not moved, until its content changes.
        if (file.sha256 in ignored) return
        val status = SyncStatus.normalize(file.status)
        kindleActions += KindleAction.Upload(file.path)
        phoneActions +=
            PhoneAction.Import(
                path = file.path,
                status = status?.let(SyncStatus::phoneName),
                statusAt = if (status != null) file.statusAt else null,
                annotations = file.annotations.distinctBy { it.id }.sortedWith(annotationOrder),
                lists = SyncPlanner.names(file.collections).toList(),
            )
        if (!tidy(file.path, file.authors, title(file), file.format)) {
            kindleActions +=
                KindleAction.Move(file.path, allocate(file.authors, title(file), file.format))
        }
    }

    // ---- Rule 5: books on both sides ----

    fun both(book: PhoneBook, file: KindleFile) {
        val b = baseline[book.id]
        val content: Side? =
            when {
                !book.hasFile -> Side.KINDLE
                book.sha256 == file.sha256 -> null
                b != null && file.sha256 == b.sha256 -> Side.PHONE
                b != null && book.sha256 == b.sha256 -> Side.KINDLE
                else -> {
                    val q =
                        question(
                            FILE_SIDES,
                            book.id,
                            file.path,
                            "${book.title} was changed on both the phone and the Kindle. " +
                                "Which copy should both keep?",
                        )
                    // Until it is answered, nothing changes for this book.
                    answer(q) ?: return
                }
            }
        // The file's own format when the phone's is not one Librillo keeps.
        val format = book.format.lowercase().takeIf(::kept) ?: file.format
        val to =
            if (tidy(file.path, book.authors, book.title, format)) file.path
            else allocate(book.authors, book.title, format)
        // A Kindle file the phone refused before is not sent again; the rest still syncs.
        if (content == Side.KINDLE && file.sha256 !in ignored) {
            kindleActions += KindleAction.Upload(file.path)
            phoneActions += PhoneAction.Revise(book.id, file.path)
        }
        if (to != file.path) kindleActions += KindleAction.Move(file.path, to)
        if (content == Side.PHONE) {
            kindleActions +=
                KindleAction.Download(book.id, book.sha256, book.size, to, replaces = file.sha256)
        }
        metadata(book, file, to, b)
    }

    // ---- Rules 6 to 8: status, annotations, lists ----

    /** Syncs what [book] and [file] (now at [to]) say about reading, against [b]. */
    fun metadata(book: PhoneBook, file: KindleFile, to: String, b: Baseline?) {
        status(book, file, to, b)
        annotations(book, file, to, b)
        collections(book, file, to, b)
    }

    /** Rule 7: each side gets what it lacks; edits and deletions cross over ([AnnotationMerge]). */
    fun annotations(book: PhoneBook, file: KindleFile, to: String, b: Baseline?) {
        val r =
            AnnotationMerge.plan(book.annotations, file.annotations, b, file.annotationsComplete)
        if (r.addToKindle.isNotEmpty())
            kindleActions += KindleAction.AddAnnotations(to, r.addToKindle)
        if (r.editOnKindle.isNotEmpty())
            kindleActions += KindleAction.EditAnnotations(to, r.editOnKindle)
        if (r.deleteOnKindle.isNotEmpty())
            kindleActions += KindleAction.DeleteAnnotations(to, r.deleteOnKindle)
        if (r.addToPhone.isNotEmpty())
            phoneActions += PhoneAction.AddAnnotations(book.id, r.addToPhone)
        if (r.editOnPhone.isNotEmpty())
            phoneActions += PhoneAction.EditAnnotations(book.id, r.editOnPhone)
        if (r.deleteOnPhone.isNotEmpty())
            phoneActions += PhoneAction.DeleteAnnotations(book.id, r.deleteOnPhone)
    }

    fun status(book: PhoneBook, file: KindleFile, to: String, b: Baseline?) {
        val phone = SyncStatus.fromPhone(book.status) ?: return
        val kindle = SyncStatus.normalize(file.status)
        fun toKindle() {
            kindleActions += KindleAction.SetStatus(to, phone)
        }
        fun toPhone(status: String) {
            phoneActions +=
                PhoneAction.SetStatus(
                    book.id,
                    SyncStatus.phoneName(status),
                    file.statusAt ?: book.statusAt,
                )
        }
        // No record on the e-reader: it never changes the phone.
        if (kindle == null) {
            if (phone != SyncStatus.UNREAD) toKindle()
            return
        }
        if (kindle == phone) return
        val was = SyncStatus.normalize(b?.status)
        if (was == null) {
            // No common ancestor: progress beats unread, then the newer change wins.
            val kindleWins =
                when {
                    phone == SyncStatus.UNREAD -> true
                    kindle == SyncStatus.UNREAD -> false
                    else -> (file.statusAt ?: Long.MIN_VALUE) > book.statusAt
                }
            if (kindleWins) toPhone(kindle) else toKindle()
            return
        }
        when {
            kindle == was -> toKindle()
            phone == was -> toPhone(kindle)
            else -> {
                val phoneSide = book.status.ifBlank { SyncStatus.phoneName(phone) }
                val kindleSide = SyncStatus.phoneName(kindle)
                val q =
                    Question(
                        id = SyncPlanner.questionId(SyncPlanner.STATUS, book.id, file.path),
                        bookId = book.id,
                        path = file.path,
                        kind = SyncPlanner.STATUS,
                        phoneSide = phoneSide,
                        kindleSide = kindleSide,
                        text =
                            "${book.title}: Kindle says $kindleSide, phone says $phoneSide. " +
                                "Which is right?",
                        options = listOf(kindleSide, phoneSide),
                    )
                when (answer(q)) {
                    Side.KINDLE -> toPhone(kindle)
                    Side.PHONE -> toKindle()
                    null -> Unit
                }
            }
        }
    }

    fun collections(book: PhoneBook, file: KindleFile, to: String, b: Baseline?) {
        val lists = SyncPlanner.names(book.lists)
        val collections = SyncPlanner.names(file.collections)
        val was = b?.collections?.let(SyncPlanner::names)
        val all = (lists + collections + was.orEmpty()).toSortedSet()
        val result = all.filter { name ->
            val p = name in lists
            val k = name in collections
            when {
                p == k -> p
                was == null -> true
                p != (name in was) -> p
                else -> k
            }
        }
        if (result.toSet() != lists) phoneActions += PhoneAction.SetLists(book.id, result)
        if (result.toSet() != collections) kindleActions += KindleAction.SetCollections(to, result)
    }

    // ---- Questions ----

    enum class Side {
        PHONE,
        KINDLE,
    }

    /**
     * The side the owner chose for [q], or null after adding [q] to the questions. An answer that
     * is not one of the options (the question has changed since) is ignored.
     */
    fun answer(q: Question): Side? =
        when (answers[q.id]) {
            q.kindleSide -> Side.KINDLE
            q.phoneSide -> Side.PHONE
            else -> {
                questions += q
                null
            }
        }

    fun question(sides: Sides, bookId: String, path: String, text: String): Question {
        val kind = if (sides === FILE_SIDES) SyncPlanner.FILE else SyncPlanner.DELETE_VS_EDIT
        return Question(
            id = SyncPlanner.questionId(kind, bookId, path),
            bookId = bookId,
            path = path,
            kind = kind,
            phoneSide = sides.phone,
            kindleSide = sides.kindle,
            text = text,
            options = listOf(sides.kindle, sides.phone),
        )
    }

    // ---- Paths ----

    /** A free path in the library folder for a book, now taken (rule 10). */
    fun allocate(authors: List<String>, title: String, format: String): String {
        val path =
            "$root/" +
                BookPaths.path(authors, title, format) { BookPaths.key("$root/$it") in occupied }
        occupied += BookPaths.key(path)
        return path
    }

    /**
     * Whether [path] is where the book belongs: its `Author/Title.ext` in the library folder, in
     * any case, or a numbered " (n)" variant of it, which a clash gave it once and it keeps.
     */
    fun tidy(path: String, authors: List<String>, title: String, format: String): Boolean {
        val wanted = BookPaths.key("$root/" + BookPaths.path(authors, title, format))
        val stem = wanted.substringBeforeLast('.')
        val ext = wanted.substringAfterLast('.')
        val key = BookPaths.key(path)
        return key == wanted ||
            Regex(Regex.escape(stem) + """ \([1-9][0-9]*\)\.""" + Regex.escape(ext)).matches(key)
    }

    fun title(file: KindleFile): String =
        file.title?.takeIf { it.isNotBlank() } ?: FileTitle.from(file.path)

    /**
     * The annotations in [from] that [have] lacks, by id, in a stable order. A tombstone in [have]
     * counts as having it; one in [from] is never sent.
     */
    fun lacking(have: List<SyncAnnotation>, from: List<SyncAnnotation>): List<SyncAnnotation> {
        val ids = have.map { it.id }.toSet()
        return from
            .distinctBy { it.id }
            .filter { it.id !in ids && !it.deleted }
            .sortedWith(annotationOrder)
    }

    companion object {
        /** Who keeps what in a delete-vs-edit question. */
        class Sides(val phone: String, val kindle: String)

        /** The phone removed or purged the book; the e-reader changed it. */
        val REMOVED_SIDES = Sides(phone = "Delete it", kindle = "Keep it")

        /** The e-reader deleted the book; the phone changed it. */
        val DELETED_SIDES = Sides(phone = "Send it back", kindle = "Remove it")

        /** The file was changed on both sides. */
        val FILE_SIDES = Sides(phone = "Phone copy", kindle = "Kindle copy")

        val annotationOrder = compareBy<SyncAnnotation>({ it.datetime }, { it.id })

        /** The path an e-reader action is about. */
        fun subject(action: KindleAction): String =
            when (action) {
                is KindleAction.Upload -> action.path
                is KindleAction.Move -> action.from
                is KindleAction.Download -> action.to
                is KindleAction.Delete -> action.path
                is KindleAction.SetStatus -> action.path
                is KindleAction.AddAnnotations -> action.path
                is KindleAction.EditAnnotations -> action.path
                is KindleAction.DeleteAnnotations -> action.path
                is KindleAction.SetCollections -> action.path
            }

        /**
         * E-reader actions run in phases: uploads, moves, downloads, deletes, then status,
         * annotations (added, edited, deleted) and collections. Each phase is in path order,
         * ignoring case (rule 12).
         */
        val kindleOrder =
            compareBy<KindleAction>(
                {
                    when (it) {
                        is KindleAction.Upload -> 0
                        is KindleAction.Move -> 1
                        is KindleAction.Download -> 2
                        is KindleAction.Delete -> 3
                        is KindleAction.SetStatus -> 4
                        is KindleAction.AddAnnotations -> 5
                        is KindleAction.EditAnnotations -> 6
                        is KindleAction.DeleteAnnotations -> 7
                        is KindleAction.SetCollections -> 8
                    }
                },
                { BookPaths.key(subject(it)) },
                { subject(it) },
            )

        /** Phone actions in phases, each in path or book order (rule 12). */
        val phoneOrder =
            compareBy<PhoneAction>(
                {
                    when (it) {
                        is PhoneAction.Import -> 0
                        is PhoneAction.Restore -> 1
                        is PhoneAction.Revise -> 2
                        is PhoneAction.Remove -> 3
                        is PhoneAction.SetStatus -> 4
                        is PhoneAction.AddAnnotations -> 5
                        is PhoneAction.EditAnnotations -> 6
                        is PhoneAction.DeleteAnnotations -> 7
                        is PhoneAction.SetLists -> 8
                    }
                },
                {
                    when (it) {
                        is PhoneAction.Import -> it.path
                        is PhoneAction.Restore -> it.bookId
                        is PhoneAction.Revise -> it.bookId
                        is PhoneAction.Remove -> it.bookId
                        is PhoneAction.SetStatus -> it.bookId
                        is PhoneAction.AddAnnotations -> it.bookId
                        is PhoneAction.EditAnnotations -> it.bookId
                        is PhoneAction.DeleteAnnotations -> it.bookId
                        is PhoneAction.SetLists -> it.bookId
                    }
                },
            )
    }
}
