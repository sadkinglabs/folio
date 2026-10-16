package dev.sadking.folio.core.sync

import dev.sadking.folio.core.FileTitle

/** Builders and a two-sided simulator for the sync planner tests. */
internal object Fx {
    const val HOME = "/mnt/us/koreader"
    const val ROOT = "Librillo"

    val A = "a".repeat(64)
    val B = "b".repeat(64)
    val C = "c".repeat(64)
    val D = "d".repeat(64)
    val E = "e".repeat(64)

    fun ann(id: String, datetime: String = "2026-01-01 10:00:00") =
        SyncAnnotation(
            id,
            "text $id",
            "",
            "Chapter 1",
            datetime,
            "/body/p[1]",
            "/body/p[2]",
            "highlight",
        )

    fun file(
        path: String,
        sha: String,
        status: String? = null,
        statusAt: Long? = null,
        annotations: List<SyncAnnotation> = emptyList(),
        collections: List<String> = emptyList(),
        title: String? = null,
        authors: List<String> = emptyList(),
        format: String = path.substringAfterLast('.').lowercase(),
        size: Long = 100,
    ) =
        KindleFile(
            path,
            size,
            sha,
            format,
            status,
            statusAt,
            annotations,
            collections,
            title,
            authors,
        )

    fun book(
        id: String,
        sha: String,
        title: String = "Home",
        authors: List<String> = listOf("Ann Lee"),
        status: String = "Unread",
        statusAt: Long = 0,
        removed: Boolean = false,
        hasFile: Boolean = true,
        annotations: List<SyncAnnotation> = emptyList(),
        lists: List<String> = emptyList(),
        format: String = "epub",
        size: Long = 100,
    ) =
        PhoneBook(
            id,
            sha,
            format,
            title,
            authors,
            status,
            statusAt,
            removed,
            hasFile,
            annotations,
            lists,
            size,
        )

    fun base(
        bookId: String,
        path: String,
        sha: String,
        status: String = "unread",
        statusAt: Long = 0,
        annotationIds: Set<String> = emptySet(),
        collections: Set<String> = emptySet(),
    ) = Baseline(bookId, path, sha, status, statusAt, annotationIds, collections)

    fun plan(
        kindle: List<KindleFile>,
        phone: List<PhoneBook>,
        baselines: List<Baseline> = emptyList(),
        answers: Map<String, String> = emptyMap(),
    ) = SyncPlanner.plan(HOME, ROOT, kindle, phone, baselines, answers)

    /** Every e-reader action reported as done. */
    fun allDone(plan: SyncPlan) = plan.kindle.indices.map { KindleResult(it, true) }

    /**
     * The phone after it applied [actions]. An import becomes book "new-<first 8 of hash>", named
     * by the uploaded file's own title and authors, as the app would read them.
     */
    fun phoneAfter(
        phone: List<PhoneBook>,
        kindleBefore: List<KindleFile>,
        actions: List<PhoneAction>,
    ): List<PhoneBook> {
        val books = LinkedHashMap(phone.associateBy { it.id })
        val uploaded = kindleBefore.associateBy { it.path }
        fun edit(id: String, change: (PhoneBook) -> PhoneBook) {
            books.computeIfPresent(id) { _, b -> change(b) }
        }
        for (action in actions) {
            when (action) {
                is PhoneAction.Import -> {
                    val f = uploaded.getValue(action.path)
                    val id = "new-" + f.sha256.take(8)
                    books[id] =
                        PhoneBook(
                            id = id,
                            sha256 = f.sha256,
                            format = f.format,
                            title = f.title?.takeIf { it.isNotBlank() } ?: FileTitle.from(f.path),
                            authors = f.authors,
                            status = action.status ?: "Unread",
                            statusAt = action.statusAt ?: 0,
                            annotations = action.annotations,
                            lists = action.lists,
                            size = f.size,
                        )
                }
                is PhoneAction.Remove -> edit(action.bookId) { it.copy(removed = true) }
                is PhoneAction.Restore -> edit(action.bookId) { it.copy(removed = false) }
                is PhoneAction.Revise -> {
                    val f = uploaded.getValue(action.path)
                    edit(action.bookId) {
                        it.copy(sha256 = f.sha256, size = f.size, hasFile = true)
                    }
                }
                is PhoneAction.SetStatus ->
                    edit(action.bookId) { it.copy(status = action.status, statusAt = action.at) }
                is PhoneAction.AddAnnotations ->
                    edit(action.bookId) { b ->
                        val have = b.annotations.map { it.id }.toSet()
                        b.copy(
                            annotations =
                                b.annotations + action.annotations.filter { it.id !in have }
                        )
                    }
                is PhoneAction.SetLists -> edit(action.bookId) { it.copy(lists = action.lists) }
            }
        }
        return books.values.toList()
    }

    /** Both sides and the baselines after one sync in which everything succeeded. */
    data class State(
        val kindle: List<KindleFile>,
        val phone: List<PhoneBook>,
        val baselines: List<Baseline>,
    )

    fun sync(state: State, answers: Map<String, String> = emptyMap()): Pair<SyncPlan, State> {
        val plan = plan(state.kindle, state.phone, state.baselines, answers)
        val results = allDone(plan)
        val kindle = SyncPlanner.kindleAfter(state.kindle, plan.kindle, state.phone, results)
        val phone =
            phoneAfter(state.phone, state.kindle, SyncPlanner.phoneActionsAfter(plan, results))
        val baselines = SyncPlanner.baselines(kindle, phone, state.baselines, ROOT)
        return plan to State(kindle, phone, baselines)
    }
}
