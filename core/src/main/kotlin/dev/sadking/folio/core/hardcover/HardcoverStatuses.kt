package dev.sadking.folio.core.hardcover

/**
 * Hardcover's reading statuses (user_books.status_id) and Librillo's words for them. Librillo's
 * "Unread" has no Hardcover status: it is no opinion, and never changes anything on Hardcover.
 */
object HardcoverStatuses {
    /** Not in the Hardcover library. Used in baselines and questions only. */
    const val NONE = 0
    const val WANT_TO_READ = 1
    const val CURRENTLY_READING = 2
    const val READ = 3
    const val PAUSED = 4
    const val DID_NOT_FINISH = 5

    /** The catalogue's word for an entry with no status of its own. */
    const val CATALOGUE_DEFAULT = "Want to Read"

    private val words =
        linkedMapOf(
            WANT_TO_READ to "Want to Read",
            CURRENTLY_READING to "Reading",
            READ to "Finished",
            PAUSED to "Paused",
            DID_NOT_FINISH to "Abandoned",
        )

    /**
     * "Want to Read"→1, "Reading"→2, "Finished"→3, "Paused"→4, "Abandoned"→5; anything else
     * (Unread, blank) → null.
     */
    fun fromPhone(word: String): Int? {
        val clean = word.trim()
        return words.entries.firstOrNull { it.value.equals(clean, ignoreCase = true) }?.key
    }

    /** 1..5 → the phone word above; anything else → null. */
    fun toPhone(status: Int): String? = words[status]

    /** A catalogue entry's status word: its own, or "Want to Read" when blank. */
    fun catalogueWord(status: String): String = status.ifBlank { CATALOGUE_DEFAULT }
}
