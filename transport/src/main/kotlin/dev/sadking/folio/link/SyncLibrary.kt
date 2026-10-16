package dev.sadking.folio.link

import dev.sadking.folio.core.sync.Baseline
import dev.sadking.folio.core.sync.PhoneAction
import dev.sadking.folio.core.sync.PhoneBook
import dev.sadking.folio.core.sync.Question
import java.io.File

/** Why the phone will not take a file from the e-reader. */
enum class Refusal {
    /** Not an EPUB, PDF, CBZ or CBR Librillo can read: unknown, damaged or protected. */
    NOT_A_BOOK,

    /** Larger than Librillo takes. */
    TOO_LARGE,

    /** A new version of a book, but a different kind of file than the book on the phone. */
    OTHER_FORMAT,
}

/**
 * A file the phone would not take from an e-reader, remembered by its content so it isn't uploaded
 * again at every sync. [path] is where it was on the e-reader when it was refused, and [at] when.
 * It is forgotten once no file on the e-reader has that content any more (changed or deleted).
 */
data class RefusedFile(val sha256: String, val path: String, val reason: Refusal, val at: Long) {
    /** The file's name, for the owner. */
    val name: String
        get() = path.substringAfterLast('/')
}

/**
 * Thrown by [SyncLibrary.receive] and [SyncLibrary.revise] when the file can never be taken as it
 * is. Returning null instead means "not now": the next sync tries again.
 */
class SyncRefusal(val reason: Refusal) : Exception("Refused: $reason")

/**
 * The phone's side of sync protocol v2 ([SyncRoutes]), implemented by the app over Room and the
 * Books folder. [ereaderId] is the id the e-reader's plugin sends as `device.id`. The routes call
 * it from the Link server's threads, one sync step at a time. docs/SYNC_PROTOCOL.md has the rules.
 *
 * Statuses: [PhoneBook.status] and the status in [PhoneAction.Import] and [PhoneAction.SetStatus]
 * are in the phone's words ("Unread", "Reading", "Finished", "Paused", ...). [Baseline.status] is
 * in the e-reader's words ("unread", "reading", "finished", "abandoned"); the app stores it as is.
 */
interface SyncLibrary {
    /** Every book, including ones in Recently removed and ones with no file. */
    fun phoneBooks(): List<PhoneBook>

    /** What the phone and [ereaderId] agreed on at the end of their last finished sync. */
    fun baselines(ereaderId: String): List<Baseline>

    /** The book's current file, or null if it has none. */
    fun bookFile(bookId: String): File?

    /**
     * Takes a file [ereaderId] uploaded from [kindlePath] as a new book (a [PhoneAction.Import]).
     * [file] is a temporary file the routes delete afterwards, so copy or move it. Returns the new
     * book's id, or null if the file was not taken this time. Throws [SyncRefusal] when it can
     * never be taken, so it is not sent again ([refused]). Called while a sync finishes, before
     * [apply].
     */
    fun receive(ereaderId: String, kindlePath: String, file: File): String?

    /**
     * Takes a file [ereaderId] uploaded from [kindlePath] as a new revision of book [bookId] (a
     * [PhoneAction.Revise]): kept beside the old file, never an overwrite. The book may be in
     * Recently removed; a [PhoneAction.Restore] for it then follows in [apply]. Returns the book's
     * id, or null if the file was not taken this time. Throws [SyncRefusal] when it can never be
     * taken. Called while a sync finishes, before [apply].
     */
    fun revise(ereaderId: String, bookId: String, kindlePath: String, file: File): String?

    /**
     * Applies the phone's side of a finished sync, in order. [received] maps each e-reader path
     * whose upload was taken to the book [receive] or [revise] returned. Import and Revise actions
     * only come when their file was received. An [PhoneAction.Import] carries the e-reader's
     * status, notes and collections (as lists) for the book `received[import.path]`; the file
     * itself is already in. A [PhoneAction.Revise] needs nothing more.
     */
    fun apply(ereaderId: String, actions: List<PhoneAction>, received: Map<String, String>)

    /**
     * Replaces every baseline of [ereaderId] with [baselines]: rows left out are deleted. Never an
     * upsert, or a book restored from Recently removed would look deleted on the e-reader.
     */
    fun commit(ereaderId: String, baselines: List<Baseline>)

    /** True when the phone has changes [ereaderId] has not had yet. */
    fun pending(ereaderId: String): Boolean

    /** Questions about [ereaderId] waiting for an answer on the phone. */
    fun phoneQuestions(ereaderId: String): List<Question>

    /**
     * Replaces the questions about [ereaderId]. Stored answers to questions not in [questions] are
     * dropped: they have been used, or the conflict is gone.
     */
    fun saveQuestions(ereaderId: String, questions: List<Question>)

    /** Answers given on the phone, by question id. */
    fun phoneAnswers(ereaderId: String): Map<String, String> = emptyMap()

    /**
     * Where uploads are written while they arrive. The Books folder's volume makes moving cheap.
     */
    fun uploadDir(): File = File(System.getProperty("java.io.tmpdir"))

    /**
     * Files [ereaderId] has that the phone refused. The planner leaves files with this content
     * alone ([dev.sadking.folio.core.sync.SyncPlanner.plan]'s `ignored`).
     */
    fun refused(ereaderId: String): List<RefusedFile> = emptyList()

    /** Replaces the refused files of [ereaderId]; called as each sync finishes. */
    fun saveRefused(ereaderId: String, refused: List<RefusedFile>) {}

    /** [ereaderId], called [deviceName], started a sync at [at]: the phone has planned it. */
    fun started(ereaderId: String, deviceName: String, at: Long) {}

    /** A sync with [ereaderId], called [deviceName] on the e-reader, finished at [at]. */
    fun synced(ereaderId: String, deviceName: String, at: Long) {}
}
