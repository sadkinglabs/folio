package dev.sadking.folio.data

import java.util.Locale

data class SearchBook(
    val book: Book,
    val text: String,
    val tags: Set<String>,
    val displayTags: List<String>,
)

fun indexLibrary(books: List<Book>): List<SearchBook> = books.map { book ->
    val tags = runCatching { decode(book.tags) }.getOrDefault(emptyList())
    SearchBook(
        book,
        (listOf(book.title, book.authorLabel, book.series) + tags)
            .joinToString(" ")
            .lowercase(Locale.ROOT),
        tags.map { it.trim().lowercase(Locale.ROOT) }.toSet(),
        tags,
    )
}

fun searchLibrary(
    index: List<SearchBook>,
    query: String,
    status: String,
    files: String,
    tag: String,
    sort: String,
    signals: Map<String, BookSignals>,
    fileIds: Set<String>,
): List<Book> {
    val needle = query.trim().lowercase(Locale.ROOT)
    val exactTag = tag.trim().lowercase(Locale.ROOT)
    val result =
        index
            .asSequence()
            .filter { entry ->
                (exactTag.isEmpty() || exactTag in entry.tags) &&
                    (files == "All books" ||
                        (files == "With EPUB") == (entry.book.id in fileIds)) &&
                    (status == "All" ||
                        (signals[entry.book.id]?.reading?.substringBefore(" ·")
                            ?: entry.book.status) == status) &&
                    entry.text.contains(needle)
            }
            .map { it.book }
            .toList()
    return when (sort) {
        "Title" ->
            result.sortedWith(compareBy<Book> { it.title.lowercase(Locale.ROOT) }.thenBy { it.id })
        "Author" ->
            result.sortedWith(
                compareBy<Book> { it.authorLabel.lowercase(Locale.ROOT) }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
            )
        else -> result.sortedByDescending { it.addedAt }
    }
}
