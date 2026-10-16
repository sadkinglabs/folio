package dev.sadking.folio.core.sync

/**
 * Reading status in the e-reader's words, and the phone's words for each. KOReader shows
 * "abandoned" as "On hold", which is the phone's "Paused"; the phone's "Abandoned" maps there too.
 */
object SyncStatus {
    const val UNREAD = "unread"
    const val READING = "reading"
    const val FINISHED = "finished"
    const val ABANDONED = "abandoned"
    val ALL = listOf(UNREAD, READING, FINISHED, ABANDONED)

    /** The e-reader's word for a status from either side, or null for blank or unknown text. */
    fun normalize(status: String?): String? =
        when (status?.trim()?.lowercase()) {
            "unread",
            "want to read" -> UNREAD
            "reading" -> READING
            "finished",
            "complete" -> FINISHED
            "abandoned",
            "paused",
            "on hold" -> ABANDONED
            else -> null
        }

    /** A phone status in the e-reader's words. A blank phone status is unread. */
    fun fromPhone(status: String): String? = normalize(status.ifBlank { UNREAD })

    /** The phone's word for an e-reader status. */
    fun phoneName(status: String): String =
        when (status) {
            UNREAD -> "Unread"
            READING -> "Reading"
            FINISHED -> "Finished"
            ABANDONED -> "Paused"
            else -> throw IllegalArgumentException("Unknown status: $status")
        }
}
