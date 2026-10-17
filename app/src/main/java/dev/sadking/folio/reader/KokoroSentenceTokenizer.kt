@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import dev.sadking.folio.reader.narration.AbbreviationJoiner
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit
import org.readium.r2.shared.util.tokenizer.Tokenizer

/**
 * Splits text into sentences for Kokoro. Readium's player and the narration planner both use it, so
 * the sentences planned ahead are exactly the ones the player then asks for. Kokoro speaks English
 * only, so the language passed in is ignored: whichever language each caller passes, the split is
 * the same. "Mr. Bennet" stays one sentence (see [AbbreviationJoiner]).
 */
internal val kokoroSentenceTokenizer: (Language?) -> Tokenizer<String, IntRange> = {
    KokoroSentenceTokenizer()
}

/** ICU's English sentence breaks, with breaks after known abbreviations joined back. */
internal class KokoroSentenceTokenizer : Tokenizer<String, IntRange> {
    // A BreakIterator holds state, so each tokenizer has its own; Readium makes one per segment.
    private val sentences = DefaultTextContentTokenizer(TextUnit.Sentence, Language("en-US"))

    override fun tokenize(data: String): List<IntRange> =
        AbbreviationJoiner.join(data, sentences.tokenize(data))
}
