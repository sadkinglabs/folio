package dev.sadking.folio.core.sync

/**
 * One highlight, note or bookmark, as both sides exchange it. [id] is its identity across devices:
 * the sender builds it from the device that made it, [datetime] and [pos0]. Two annotations with
 * the same id are the same annotation.
 *
 * [color] and [drawer] are KOReader's highlight colour ("yellow", "green", ...) and drawing style
 * ("lighten", "underscore", ...), blank when unknown. [updated] is when the note, colour or style
 * last changed, in KOReader's form (`YYYY-MM-DD HH:MM:SS`, local time), blank when never. [deleted]
 * is only ever set on the phone's side: the owner deleted it there, and the row is kept as a
 * tombstone. Rule 7 of docs/SYNC_PROTOCOL.md says when a deletion or an edit crosses over.
 */
data class SyncAnnotation(
    val id: String,
    val text: String,
    val note: String,
    val chapter: String,
    val datetime: String,
    val pos0: String,
    val pos1: String,
    /** "highlight", "note" or "bookmark". */
    val kind: String,
    val color: String = "",
    val drawer: String = "",
    val updated: String = "",
    val deleted: Boolean = false,
)

/**
 * A book file on the e-reader, as the plugin reports it. [path] is relative to KOReader's home
 * folder, with "/" separators. [status] is in the e-reader's words ([SyncStatus]): "unread",
 * "reading", "finished", "abandoned", or null when KOReader has no record of the book. [statusAt]
 * is when it last changed, in epoch milliseconds. [title] and [authors] are KOReader's metadata for
 * the file, used to name a book the phone doesn't have yet.
 *
 * [annotationsComplete] is true when the plugin read the book's notes in full and takes edits and
 * deletions for them (plugin 2026-10 and later). Only then can an annotation missing here be a
 * deletion, and only then are edits and deletions sent. Older plugins never set it.
 */
data class KindleFile(
    val path: String,
    val size: Long,
    val sha256: String,
    val format: String,
    val status: String? = null,
    val statusAt: Long? = null,
    val annotations: List<SyncAnnotation> = emptyList(),
    val collections: List<String> = emptyList(),
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val annotationsComplete: Boolean = false,
)

/**
 * A book in Librillo. [status] is in the phone's words ("Unread", "Reading", "Finished", "Paused",
 * "Abandoned", "Want to Read"); [statusAt] is when it last changed. [removed] is true for a book in
 * Recently removed. [hasFile] is false for a record without a file. [lists] are the names of the
 * lists the book is in, matched to KOReader collections by name. [size] is the file's size in
 * bytes, copied into downloads; 0 when unknown.
 */
data class PhoneBook(
    val id: String,
    val sha256: String,
    val format: String,
    val title: String,
    val authors: List<String>,
    val status: String,
    val statusAt: Long,
    val removed: Boolean = false,
    val hasFile: Boolean = true,
    val annotations: List<SyncAnnotation> = emptyList(),
    val lists: List<String> = emptyList(),
    val size: Long = 0,
)

/**
 * What the phone and one e-reader agreed about a book at their last sync: the common ancestor of
 * the 3-way compare. [status] is in the e-reader's words, or "" when the two sides did not agree.
 * [annotationIds] are the annotations both sides had. [annotationStates] maps each of them whose
 * note, colour and style both sides agreed on to [AnnotationMerge.state]; it is null when the
 * e-reader did not take edits and deletions at that sync (an older plugin).
 */
data class Baseline(
    val bookId: String,
    val path: String,
    val sha256: String,
    val status: String,
    val statusAt: Long,
    val annotationIds: Set<String> = emptySet(),
    val collections: Set<String> = emptySet(),
    val annotationStates: Map<String, String>? = null,
)

/** What the e-reader does. Paths are relative to KOReader's home folder. */
sealed interface KindleAction {
    /**
     * Fetch book [bookId] and save it at [to]. When [replaces] is set, [to] already holds a file
     * with that SHA-256, and the download replaces it in place, keeping its `.sdr`. Without it,
     * nothing is at [to].
     */
    data class Download(
        val bookId: String,
        val sha256: String,
        val size: Long,
        val to: String,
        val replaces: String? = null,
    ) : KindleAction

    /** Rename a file, with its `.sdr`, history and collections. [to] is free. */
    data class Move(val from: String, val to: String) : KindleAction

    /** Delete a file and its `.sdr`. */
    data class Delete(val path: String) : KindleAction

    /** Send this file to the phone. */
    data class Upload(val path: String) : KindleAction

    /** Set KOReader's status, in the e-reader's words. */
    data class SetStatus(val path: String, val status: String) : KindleAction

    /** Add these annotations; the ones already there (same id) are skipped. */
    data class AddAnnotations(val path: String, val annotations: List<SyncAnnotation>) :
        KindleAction

    /**
     * Set the note, colour, style and edit time of these annotations, found by id. Only sent to a
     * plugin that takes it ([KindleFile.annotationsComplete]).
     */
    data class EditAnnotations(val path: String, val annotations: List<SyncAnnotation>) :
        KindleAction

    /**
     * Delete these annotations, by id. Only sent to a plugin that takes it, for annotations the
     * owner deleted on the phone that both sides had at the last sync.
     */
    data class DeleteAnnotations(val path: String, val ids: List<String>) : KindleAction

    /** The book's collections become exactly these. */
    data class SetCollections(val path: String, val collections: List<String>) : KindleAction
}

/** What the phone does, after the e-reader has reported its results. */
sealed interface PhoneAction {
    /**
     * Add the file uploaded from [path] as a new book, with the e-reader's status (in the phone's
     * words), annotations and collections as lists.
     */
    data class Import(
        val path: String,
        val status: String? = null,
        val statusAt: Long? = null,
        val annotations: List<SyncAnnotation> = emptyList(),
        val lists: List<String> = emptyList(),
    ) : PhoneAction

    /** Move the book to Recently removed. */
    data class Remove(val bookId: String) : PhoneAction

    /** Bring the book back from Recently removed. */
    data class Restore(val bookId: String) : PhoneAction

    /** Set the book's status, in the phone's words, as of [at]. */
    data class SetStatus(val bookId: String, val status: String, val at: Long) : PhoneAction

    /** Add these annotations; the ones already there (same id) are skipped. */
    data class AddAnnotations(val bookId: String, val annotations: List<SyncAnnotation>) :
        PhoneAction

    /**
     * Set the note, colour, style and edit time of these annotations, by id. One the owner had
     * deleted on the phone comes back: it was edited on the e-reader, and an edit beats a delete.
     */
    data class EditAnnotations(val bookId: String, val annotations: List<SyncAnnotation>) :
        PhoneAction

    /** Delete these annotations (deleted on the e-reader): soft, so they can be restored. */
    data class DeleteAnnotations(val bookId: String, val ids: List<String>) : PhoneAction

    /** The book's lists become exactly these. */
    data class SetLists(val bookId: String, val lists: List<String>) : PhoneAction

    /** Take the file uploaded from [path] as the book's new revision. */
    data class Revise(val bookId: String, val path: String) : PhoneAction
}

/**
 * Something only the owner can decide. Nothing changes for what it is about until it is answered.
 * The answer is one of [options]: [kindleSide] keeps the e-reader's version, [phoneSide] the
 * phone's. [id] stays the same from one sync to the next.
 */
data class Question(
    val id: String,
    val bookId: String?,
    val path: String?,
    /** "status", "file" or "delete-vs-edit". */
    val kind: String,
    val phoneSide: String,
    val kindleSide: String,
    val text: String,
    val options: List<String>,
)

/**
 * Everything one sync does. [kindle] runs in order on the e-reader. [phone] runs after the e-reader
 * reports. [matched] maps each e-reader path to the phone book it holds.
 */
data class SyncPlan(
    val kindle: List<KindleAction>,
    val phone: List<PhoneAction>,
    val questions: List<Question>,
    val matched: Map<String, String>,
) {
    val isEmpty: Boolean
        get() = kindle.isEmpty() && phone.isEmpty() && questions.isEmpty()
}

/**
 * How one e-reader action went. [index] is its position in [SyncPlan.kindle]; [path] is where the
 * file ended up when the e-reader chose a different name.
 */
data class KindleResult(
    val index: Int,
    val ok: Boolean,
    val path: String? = null,
    val error: String? = null,
)
