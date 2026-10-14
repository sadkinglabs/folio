package dev.sadking.folio.data

import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** OCR is a search hint, never an identity assertion. The user confirms every catalogue match. */
object BookDiscovery {
    fun query(text: String): String =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.length >= 3 && it.any(Char::isLetterOrDigit) }
            .take(8)
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(300)

    fun archiveSearch(title: String, authors: List<String>): String {
        val q = (listOf(title) + authors).filter { it.isNotBlank() }.joinToString(" ").take(600)
        require(q.isNotBlank())
        return "https://annas-archive.pk/search?q=" + URLEncoder.encode(q, "UTF-8")
    }

    private fun normalized(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .lowercase(Locale.ROOT)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    fun suggests(wish: WishBook, book: Book): Boolean =
        normalized(wish.title) == normalized(book.title) &&
            decode(wish.authors).isNotEmpty() &&
            decode(wish.authors).any { author ->
                decode(book.authors).any { normalized(it) == normalized(author) }
            }
}
