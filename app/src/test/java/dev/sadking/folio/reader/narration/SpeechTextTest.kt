package dev.sadking.folio.reader.narration

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    private fun say(text: String) = SpeechText.forSynthesis(text)

    @Test
    fun expandsTitles() {
        assertEquals(
            "My dear Mister Bennet, said his lady.",
            say("My dear Mr. Bennet, said his lady."),
        )
        assertEquals("Missus Bennet sighed.", say("Mrs. Bennet sighed."))
        assertEquals("Miz Lucas came.", say("Ms. Lucas came."))
        assertEquals("Doctor Watson nodded.", say("Dr. Watson nodded."))
        assertEquals("Professor Moriarty left.", say("Prof. Moriarty left."))
        assertEquals("cats versus dogs", say("cats vs. dogs"))
    }

    @Test
    fun expandsLatinAbbreviationsKeepingCommas() {
        assertEquals("Fruit, for example, apples.", say("Fruit, e.g., apples."))
        assertEquals("Fruit, for example apples.", say("Fruit, e.g. apples."))
        assertEquals("For example apples.", say("E.g. apples."))
        assertEquals("one thing, that is the last", say("one thing, i.e. the last"))
        assertEquals("That is never.", say("I.e. never."))
        assertEquals("Cities, for example Paris.", say("Cities, e.g. Paris."))
    }

    @Test
    fun etCeteraKeepsTheFullStopWhenItEndsTheSentence() {
        assertEquals("pens, ink, et cetera and more", say("pens, ink, etc. and more"))
        assertEquals("pens, ink, et cetera.", say("pens, ink, etc."))
        assertEquals("pens, ink, et cetera. Then we left.", say("pens, ink, etc. Then we left."))
        assertEquals("(pens, ink, et cetera.)", say("(pens, ink, etc.)"))
    }

    @Test
    fun saintOrStreetIsLeftAlone() {
        assertEquals("St. Paul's was cold.", say("St. Paul's was cold."))
    }

    @Test
    fun doctorOnlyBeforeACapitalisedWord() {
        assertEquals("Dr. who knows", say("Dr. who knows"))
    }

    @Test
    fun stripsInvisibleCharacters() {
        val soft = Char(0xAD)
        val zws = Char(0x200B)
        val bom = Char(0xFEFF)
        val zwnj = Char(0x200C)
        val zwj = Char(0x200D)
        assertEquals(
            "softhyphen zerowidth joined",
            say("soft${soft}hyphen zero${zws}width$bom jo${zwnj}in${zwj}ed"),
        )
    }

    @Test
    fun collapsesWhitespaceAndKeepsPunctuation() {
        assertEquals(
            "“Well,” said he; “no!” — fine…",
            say("  “Well,”  said\n he;\t“no!” — fine…  "),
        )
    }

    @Test
    fun normalizeKeyIgnoresWhitespaceOnly() {
        assertEquals("a b", normalizeKey(" a\n\t b "))
        assertEquals("Mr. A, said B.", normalizeKey("Mr.  A, said B."))
        assertEquals("Case Stays", normalizeKey("Case Stays"))
    }
}
