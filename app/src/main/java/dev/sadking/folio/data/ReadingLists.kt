package dev.sadking.folio.data

/** Built-in views of active library entries, never duplicate membership rows. */
enum class ReadingList(val title: String, val status: String) {
    WANT_TO_READ("Want to Read", "Want to Read"),
    READING("Currently Reading", "Reading"),
    FINISHED("Finished", "Finished");

    fun contains(book: Book, signals: BookSignals?): Boolean =
        (signals?.reading?.substringBefore(" ·") ?: book.status) == status
}

/** Retain the catalogue reading intent after attachment unless the EPUB has reading activity. */
fun linkedReadingSignals(
    owned: BookSignals,
    wish: WishBook?,
    observation: HardcoverObservation?,
): BookSignals =
    if (owned.reading == "Unread" && wish != null)
        owned.copy(reading = catalogueSignals(wish, observation).reading)
    else owned
