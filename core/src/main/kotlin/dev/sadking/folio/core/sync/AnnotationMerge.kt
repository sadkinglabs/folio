package dev.sadking.folio.core.sync

import java.security.MessageDigest

/**
 * Rule 7 of docs/SYNC_PROTOCOL.md: one book's annotations on the phone and on the e-reader,
 * compared against what both had at the last sync. Pure.
 *
 * - An annotation on one side only is added to the other, unless the other side deleted it: it is
 *   in the baseline, the e-reader took edits at this sync, and it was not edited since. An edit
 *   beats a deletion.
 * - The phone's deletions are tombstones ([SyncAnnotation.deleted]); the e-reader's are annotations
 *   missing from a complete list ([KindleFile.annotationsComplete]). Without a baseline, or from an
 *   older plugin, nothing is ever deleted.
 * - Note, colour and style: the side that changed since the baseline wins; when both did, or there
 *   is no baseline, each field goes to the newer edit, and a blank never beats a value unless its
 *   side is strictly newer.
 */
object AnnotationMerge {
    /** What one book's annotations need on each side. Every list is in a stable order. */
    data class Result(
        val addToKindle: List<SyncAnnotation> = emptyList(),
        val editOnKindle: List<SyncAnnotation> = emptyList(),
        val deleteOnKindle: List<String> = emptyList(),
        val addToPhone: List<SyncAnnotation> = emptyList(),
        val editOnPhone: List<SyncAnnotation> = emptyList(),
        val deleteOnPhone: List<String> = emptyList(),
    )

    private val order = compareBy<SyncAnnotation>({ it.datetime }, { it.id })

    /**
     * Compares [phone] (tombstones included) with [kindle] against [baseline]. [edits] is the
     * e-reader's [KindleFile.annotationsComplete]: without it, the old rule holds (each side gets
     * the ones it lacks, nothing is edited or deleted).
     */
    fun plan(
        phone: List<SyncAnnotation>,
        kindle: List<SyncAnnotation>,
        baseline: Baseline?,
        edits: Boolean,
    ): Result {
        val phoneById = phone.distinctBy { it.id }.associateBy { it.id }
        val kindleById = kindle.distinctBy { it.id }.associateBy { it.id }
        val known = baseline?.annotationIds.orEmpty()
        val states = baseline?.annotationStates
        val addToKindle = mutableListOf<SyncAnnotation>()
        val editOnKindle = mutableListOf<SyncAnnotation>()
        val deleteOnKindle = mutableListOf<SyncAnnotation>()
        val addToPhone = mutableListOf<SyncAnnotation>()
        val editOnPhone = mutableListOf<SyncAnnotation>()
        val deleteOnPhone = mutableListOf<SyncAnnotation>()
        for (id in (phoneById.keys + kindleById.keys).toSortedSet()) {
            val p = phoneById[id]
            val k = kindleById[id]
            // Both had it at the last sync, and the e-reader's list can be trusted to be whole.
            val agreed = edits && id in known
            when {
                p == null -> addToPhone += k!!
                k == null ->
                    when {
                        p.deleted -> Unit
                        // Deleted on the e-reader since the last sync, unless edited here since.
                        agreed && !edited(p, states) -> deleteOnPhone += p
                        else -> addToKindle += p.copy(deleted = false)
                    }
                p.deleted ->
                    when {
                        !agreed -> Unit
                        // Edited on the e-reader since: the edit wins, and it comes back here.
                        edited(k, states) -> editOnPhone += k
                        else -> deleteOnKindle += k
                    }
                edits -> {
                    val merged = merge(p, k, states?.get(id))
                    if (state(merged) != state(p)) editOnPhone += merged
                    if (state(merged) != state(k)) editOnKindle += merged
                }
            }
        }
        return Result(
            addToKindle = addToKindle.sortedWith(order),
            editOnKindle = editOnKindle.sortedWith(order),
            deleteOnKindle = deleteOnKindle.sortedWith(order).map { it.id },
            addToPhone = addToPhone.sortedWith(order),
            editOnPhone = editOnPhone.sortedWith(order),
            deleteOnPhone = deleteOnPhone.sortedWith(order).map { it.id },
        )
    }

    /**
     * The fingerprint of what can be edited: note, colour and style. The first 16 hex characters of
     * SHA-256 of `note + "\n" + color + "\n" + drawer`, colour and style in lower case.
     */
    fun state(a: SyncAnnotation): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                (a.note + "\n" + a.color.lowercase() + "\n" + a.drawer.lowercase()).toByteArray(
                    Charsets.UTF_8
                )
            )
            .take(8)
            .joinToString("") { "%02x".format(it) }

    /**
     * Whether [a] was edited since the last sync: its fingerprint differs from the baseline's, or,
     * when the baseline has none for it, it was ever edited at all. Unsure counts as edited, so a
     * deletion never wins on a guess.
     */
    fun edited(a: SyncAnnotation, states: Map<String, String>?): Boolean {
        val base = states?.get(a.id)
        return if (base != null) state(a) != base
        else a.updated.isNotBlank() && a.updated > a.datetime
    }

    /** When [a] last changed: its edit time, else its creation time. */
    private fun changedAt(a: SyncAnnotation) = a.updated.ifBlank { a.datetime }

    /**
     * Both sides have [p] and [k] (same id). The side whose fingerprint is still [base] did not
     * change, so the other wins. Otherwise field by field, the newer edit wins; a tie goes to the
     * phone, and a blank never beats a value unless its side is strictly newer.
     */
    internal fun merge(p: SyncAnnotation, k: SyncAnnotation, base: String?): SyncAnnotation {
        val ps = state(p)
        val ks = state(k)
        if (ps == ks) return p
        if (base != null && ps == base) return fields(p, k, k.note, k.color, k.drawer, k.updated)
        if (base != null && ks == base) return p
        val newer = changedAt(p).compareTo(changedAt(k))
        fun pick(phone: String, kindle: String): String =
            when {
                phone == kindle -> phone
                phone.isEmpty() -> if (newer > 0) phone else kindle
                kindle.isEmpty() -> if (newer < 0) kindle else phone
                else -> if (newer >= 0) phone else kindle
            }
        return fields(
            p,
            k,
            pick(p.note, k.note),
            pick(p.color.lowercase(), k.color.lowercase()),
            pick(p.drawer.lowercase(), k.drawer.lowercase()),
            maxOf(changedAt(p), changedAt(k)).takeIf {
                p.updated.isNotBlank() || k.updated.isNotBlank()
            } ?: "",
        )
    }

    /** [p] with the merged fields; a highlight with a note is a note, and back. */
    private fun fields(
        p: SyncAnnotation,
        k: SyncAnnotation,
        note: String,
        color: String,
        drawer: String,
        updated: String,
    ): SyncAnnotation {
        val kind =
            when {
                p.kind == "bookmark" || k.kind == "bookmark" -> p.kind
                note.isNotBlank() -> "note"
                else -> "highlight"
            }
        return p.copy(
            note = note,
            color = color,
            drawer = drawer,
            updated = updated,
            kind = kind,
            deleted = false,
        )
    }
}
