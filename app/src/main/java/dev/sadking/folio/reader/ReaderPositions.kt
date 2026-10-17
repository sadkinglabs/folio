@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.positionsByReadingOrder

/**
 * Readium's positions for one book, in reading order, with where each chapter starts. The slider
 * jumps to [locators] entries through the navigator, like any other navigation, so the usual
 * debounced save runs.
 */
class ReaderPositions(
    val locators: List<Locator>,
    /** Where each reading-order item starts, as a 0..1 fraction; aligned with [chapterTitles]. */
    val chapterStarts: List<Float>,
    val chapterTitles: List<String?>,
    /** The ticks drawn on the slider: chapter starts after the first, thinned. */
    val ticks: List<Float>,
    /**
     * Where each reading-order item starts, as a 0-based index into [locators]; aligned with
     * [chapterTitles]. Time left in the chapter counts to the next start.
     */
    val chapterFirst: List<Int> = emptyList(),
) {
    companion object {
        /** Ticks closer together than 1% of the book collapse into one. */
        const val MIN_TICK_GAP = 0.01f

        suspend fun compute(publication: Publication): ReaderPositions {
            val byChapter = publication.positionsByReadingOrder()
            val counts = byChapter.map { it.size }
            val total = counts.sum()
            val tocTitles = tocTitles(publication.tableOfContents)
            var start = 0
            val starts = mutableListOf<Float>()
            val firsts = mutableListOf<Int>()
            val titles = mutableListOf<String?>()
            publication.readingOrder.forEachIndexed { index, link ->
                starts += if (total > 0) start.toFloat() / total else 0f
                firsts += start
                titles += tocTitles[resourcePath(link.url().toString())] ?: link.title
                start += counts.getOrElse(index) { 0 }
            }
            return ReaderPositions(
                byChapter.flatten(),
                starts,
                titles,
                thinTicks(chapterTicks(counts), MIN_TICK_GAP),
                firsts,
            )
        }

        /** The first table-of-contents title for each resource, so a chapter can be named. */
        private fun tocTitles(links: List<Link>): Map<String, String> {
            val titles = mutableMapOf<String, String>()
            fun visit(entries: List<Link>) {
                entries.forEach { link ->
                    val title = link.title
                    if (title != null)
                        titles.putIfAbsent(resourcePath(link.url().toString()), title)
                    visit(link.children)
                }
            }
            visit(links)
            return titles
        }
    }
}
