package dev.sadking.folio.core.dictionary

import dev.sadking.folio.core.dictionary.PartOfSpeech.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphyTest {
    /** A tiny index and exception list, shaped like WordNet's. */
    private val index =
        mapOf(
            NOUN to
                setOf(
                    "running",
                    "goose",
                    "child",
                    "study",
                    "box",
                    "glass",
                    "glasses",
                    "man",
                    "church",
                    "ice_cream",
                ),
            VERB to setOf("run", "study", "box", "use", "hop"),
            ADJECTIVE to setOf("good", "well", "better", "happy", "large", "great"),
            ADVERB to setOf("well", "better", "fast"),
        )
    private val exceptionLists =
        mapOf(
            VERB to mapOf("ran" to listOf("run"), "running" to listOf("run")),
            NOUN to mapOf("geese" to listOf("goose"), "children" to listOf("child")),
            ADJECTIVE to mapOf("better" to listOf("good", "well"), "happier" to listOf("happy")),
            ADVERB to mapOf("better" to listOf("well")),
        )

    private fun lemmas(word: String, pos: PartOfSpeech) =
        Morphy.lemmas(
            word,
            pos,
            { p, w -> exceptionLists[p]?.get(w).orEmpty() },
            { p, w -> w in index.getValue(p) },
        )

    @Test
    fun runningIsRunThroughTheExceptionList() {
        assertEquals(listOf("run"), lemmas("running", VERB))
        // "running" is also a noun in its own right.
        assertEquals(listOf("running"), lemmas("running", NOUN))
    }

    @Test fun ranIsRun() = assertEquals(listOf("run"), lemmas("ran", VERB))

    @Test fun geeseIsGoose() = assertEquals(listOf("goose"), lemmas("geese", NOUN))

    @Test
    fun childrenIsChildThroughExceptions() = assertEquals(listOf("child"), lemmas("children", NOUN))

    @Test
    fun betterIsGoodAndWell() {
        val adjectives = lemmas("better", ADJECTIVE)
        assertEquals("the word itself comes first", "better", adjectives.first())
        assertTrue(adjectives.containsAll(listOf("good", "well")))
        assertEquals(listOf("better", "well"), lemmas("better", ADVERB))
    }

    @Test
    fun studiesIsStudyByTheRules() {
        assertEquals(listOf("study"), lemmas("studies", NOUN))
        assertEquals(listOf("study"), lemmas("studies", VERB))
    }

    @Test fun happierIsHappy() = assertEquals(listOf("happy"), lemmas("happier", ADJECTIVE))

    @Test fun boxesIsBox() = assertEquals(listOf("box"), lemmas("boxes", NOUN))

    @Test
    fun otherNounRules() {
        assertEquals(listOf("church"), lemmas("churches", NOUN))
        assertEquals(listOf("man"), lemmas("men", NOUN))
        // Both the word and its singular are nouns.
        assertEquals(listOf("glasses", "glass"), lemmas("glasses", NOUN))
        assertEquals(listOf("ice_cream"), lemmas("ice_creams", NOUN))
    }

    @Test
    fun verbAndAdjectiveRules() {
        assertEquals(listOf("use"), lemmas("used", VERB))
        assertEquals(listOf("use"), lemmas("using", VERB))
        assertEquals(listOf("large"), lemmas("largest", ADJECTIVE))
        assertEquals(listOf("great"), lemmas("greater", ADJECTIVE))
    }

    @Test
    fun adverbsHaveNoRulesAndUnknownWordsNothing() {
        assertEquals(emptyList<String>(), lemmas("fasts", ADVERB))
        assertEquals(emptyList<String>(), lemmas("zzz", NOUN))
        assertEquals(emptyList<String>(), lemmas("", NOUN))
        // A suffix alone is never stripped to nothing.
        assertEquals(emptyList<String>(), lemmas("s", NOUN))
    }
}
