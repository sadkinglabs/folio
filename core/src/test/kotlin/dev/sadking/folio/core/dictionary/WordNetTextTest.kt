package dev.sadking.folio.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WordNetTextTest {
    private fun n(text: String) = WordNetText.normalise(text)

    @Test
    fun aPlainWordIsLowerCased() {
        assertEquals("orchard", n("Orchard"))
        assertEquals("orchard", n("  orchard \n"))
    }

    @Test
    fun trailingAndLeadingPunctuationAndQuotesGo() {
        assertEquals("orchard", n("orchard,"))
        assertEquals("orchard", n("“orchard.”"))
        assertEquals("orchard", n("(orchard)"))
        assertEquals("orchard", n("‘orchard’…"))
        assertEquals("orchard", n("\"orchard!\""))
    }

    @Test
    fun possessivesAreDroppedWithEitherApostrophe() {
        assertEquals("dog", n("Dog's"))
        assertEquals("dog", n("Dog’s"))
        assertEquals("dog", n("dog’s,"))
        assertEquals("dogs", n("dogs’"))
    }

    @Test
    fun curlyApostrophesInsideAWordBecomeStraight() {
        assertEquals("o'clock", n("o’clock"))
    }

    @Test
    fun phrasesAreJoinedWithUnderscores() {
        assertEquals("ice_cream", n("ice cream"))
        assertEquals("in_the_long_run", n("In the\nlong  run."))
        assertEquals("well-known", n("well-known"))
    }

    @Test
    fun longSelectionsAndNonWordsAreRefused() {
        assertNull(n("the orchard had been planted by someone"))
        assertNull(n("a".repeat(WordNetText.MAX_CHARS + 1)))
        assertNull(n(""))
        assertNull(n("  …  "))
        assertNull(n("1984"))
        assertNull(n("orchard—river"))
    }
}
