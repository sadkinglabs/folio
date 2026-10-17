@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import dev.sadking.folio.reader.narration.PlannedUtterance
import dev.sadking.folio.reader.narration.normalizeKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.content.Content
import org.readium.r2.shared.publication.services.content.TextContentTokenizer
import org.readium.r2.shared.publication.services.content.content
import org.readium.r2.shared.util.Language

/** One forward walk through the book's utterances. One call at a time. */
internal interface PlanWalker {
    /**
     * Up to [max] utterances after the last one returned; empty at the end of the book. Indices
     * count from 0 within the walk; the engine renumbers them.
     */
    suspend fun next(max: Int): List<PlannedUtterance>
}

/** Starts walks through a book; the engine's view of [UtterancePlanner], faked in tests. */
internal fun interface PlanSource {
    /** A walk from [from]; null is the start of the book, as in Readium. */
    fun walk(from: Locator?): PlanWalker
}

/**
 * Lists the utterances Readium's player will ask for, in the same order and with the same text: it
 * mirrors `TtsUtteranceIterator` (Readium 3.4.0). The book's content is iterated from a locator;
 * each element is split with [TextContentTokenizer] and [kokoroSentenceTokenizer]; each text
 * segment with a letter or digit is one utterance, and so is each non-blank textual element.
 * Parsing runs on [Dispatchers.Default], never on Main.
 */
internal class UtterancePlanner(private val publication: Publication) : PlanSource {
    override fun walk(from: Locator?): PlanWalker = Walker(from)

    private inner class Walker(private val from: Locator?) : PlanWalker {
        private var elements: Content.Iterator? = null
        private var started = false
        private var ordinal = -1L
        private var index = 0L
        private val pending = ArrayDeque<PlannedUtterance>()
        private val splitter =
            TextContentTokenizer(
                language = Language("en-US"),
                overrideContentLanguage = false,
                textTokenizerFactory = kokoroSentenceTokenizer,
            )

        override suspend fun next(max: Int): List<PlannedUtterance> =
            withContext(Dispatchers.Default) {
                if (!started) {
                    started = true
                    elements = publication.content(from)?.iterator()
                }
                val iterator = elements
                while (pending.size < max && iterator != null) {
                    val element = iterator.nextOrNull() ?: break
                    ordinal++
                    val texts = splitter.tokenize(element).flatMap(::utteranceTexts)
                    val href = element.locator.href.toString()
                    texts.forEachIndexed { i, text ->
                        pending +=
                            PlannedUtterance(
                                index = index++,
                                key = normalizeKey(text),
                                text = text,
                                href = href,
                                elementOrdinal = ordinal,
                                endsSentence = endsSentence(text),
                                endsElement = i == texts.lastIndex,
                            )
                    }
                }
                List(minOf(max, pending.size)) { pending.removeFirst() }
            }
    }
}

/**
 * Starts [source]'s walk from [start] at once, so the first sentences are parsed while the voice
 * model loads; the engine's first walk from [start] picks it up.
 */
internal class PrefetchingPlanSource(
    private val source: PlanSource,
    private val start: Locator?,
    scope: CoroutineScope,
    count: Int,
) : PlanSource {
    private var first: PlanWalker? = source.walk(start)
    private val batch: Deferred<Result<List<PlannedUtterance>>> =
        scope.async(start = CoroutineStart.DEFAULT) { runCatching { first!!.next(count) } }

    override fun walk(from: Locator?): PlanWalker {
        val walker = first?.takeIf { from == start } ?: return source.walk(from)
        first = null
        return object : PlanWalker {
            private var buffered: ArrayDeque<PlannedUtterance>? = null

            override suspend fun next(max: Int): List<PlannedUtterance> {
                val buffer =
                    buffered ?: ArrayDeque(batch.await().getOrThrow()).also { buffered = it }
                if (buffer.isEmpty()) return walker.next(max)
                return List(minOf(max, buffer.size)) { buffer.removeFirst() }
            }
        }
    }
}

/** The utterances Readium makes of one element: one per text segment with a letter or digit. */
internal fun utteranceTexts(element: Content.Element): List<String> =
    when (element) {
        is Content.TextElement -> element.segments.map { it.text }.filter(::spoken)
        is Content.TextualElement ->
            listOfNotNull(element.text?.takeIf { it.isNotBlank() && spoken(it) })
        else -> emptyList()
    }

private fun spoken(text: String) = text.any { it.isLetterOrDigit() }

private val SENTENCE_END = Regex("[.!?…][\"”’')\\]]*$")

/** True when [text] ends a sentence: `.`, `!`, `?` or `…`, perhaps inside closing quotes. */
internal fun endsSentence(text: String): Boolean = SENTENCE_END.containsMatchIn(text.trimEnd())
