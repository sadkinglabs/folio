@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package dev.sadking.folio.reader

import dev.sadking.folio.reader.narration.AbbreviationJoiner
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.Language
import org.readium.r2.shared.util.tokenizer.DefaultTextContentTokenizer
import org.readium.r2.shared.util.tokenizer.TextUnit
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KokoroSentenceTokenizerTest {
    private val samples =
        listOf(
            "\"My dear Mr. Bennet,\" said his lady to him one day, \"have you heard that " +
                "Netherfield Park is let at last?\" Mr. Bennet replied that he had not.",
            "Her small pleasures, e.g. a call at Lucas Lodge, were enough. J. Austen wrote it.",
            "\"No,\" said she. \"Yes.\" He lived at No. 5 Baker Street. No. He left.",
            "It was the best of times; it was the worst of times.",
        )

    private fun split(text: String, language: Language? = null) =
        kokoroSentenceTokenizer(language).tokenize(text).map { text.substring(it) }

    @Test
    fun rangesAreReadiumsSentencesWithAbbreviationsJoined() {
        for (text in samples) {
            val readium = DefaultTextContentTokenizer(TextUnit.Sentence, Language("en-US"))
            assertEquals(
                AbbreviationJoiner.join(text, readium.tokenize(text)),
                kokoroSentenceTokenizer(null).tokenize(text),
            )
        }
    }

    @Test
    fun aTitleWithItsNameStaysOneSentence() {
        val sentences = split(samples[0])
        assertEquals(
            listOf(
                "\"My dear Mr. Bennet,\" said his lady to him one day, \"have you heard that " +
                    "Netherfield Park is let at last?\"",
                "Mr. Bennet replied that he had not.",
            ),
            sentences,
        )
    }

    @Test
    fun theLanguagePassedInChangesNothing() {
        for (text in samples) {
            val expected = split(text)
            for (language in
                listOf(Language("en-GB"), Language("fr"), Language("de"), null)) assertEquals(
                expected,
                split(text, language),
            )
        }
    }
}
