package dev.sadking.folio.reader.narration

import org.junit.Assert.assertEquals
import org.junit.Test

class AbbreviationJoinerTest {
    /**
     * The text of [pieces] joined by spaces, and each piece's range, as a sentence tokenizer gives.
     */
    private fun split(vararg pieces: String): Pair<String, List<IntRange>> {
        val ranges = ArrayList<IntRange>()
        val text = StringBuilder()
        for (piece in pieces) {
            if (text.isNotEmpty()) text.append(' ')
            ranges += text.length until text.length + piece.length
            text.append(piece)
        }
        return text.toString() to ranges
    }

    private fun joined(vararg pieces: String): List<String> {
        val (text, ranges) = split(*pieces)
        return AbbreviationJoiner.join(text, ranges).map { text.substring(it.first, it.last + 1) }
    }

    @Test
    fun titleJoinsTheName() {
        assertEquals(
            listOf("My dear Mr. Bennet, said his lady."),
            joined("My dear Mr.", "Bennet, said his lady."),
        )
    }

    @Test
    fun severalAbbreviationsInARowJoinIntoOne() {
        assertEquals(listOf("Mr. and Mrs. Smith came."), joined("Mr.", "and Mrs.", "Smith came."))
    }

    @Test
    fun latinAbbreviationsJoin() {
        assertEquals(listOf("Take e.g. this one."), joined("Take e.g.", "this one."))
        assertEquals(listOf("That is, i.e. the end."), joined("That is, i.e.", "the end."))
    }

    @Test
    fun aSingleInitialJoins() {
        assertEquals(listOf("J. Austen wrote it."), joined("J.", "Austen wrote it."))
    }

    @Test
    fun numberAbbreviationJoinsOnlyBeforeADigit() {
        assertEquals(listOf("No. 5 was there."), joined("No.", "5 was there."))
        assertEquals(listOf("No.", "He left."), joined("No.", "He left."))
    }

    @Test
    fun dialogueStaysTwoSentences() {
        assertEquals(
            listOf("\"No,\" said she.", "\"Yes.\""),
            joined("\"No,\" said she.", "\"Yes.\""),
        )
    }

    @Test
    fun ordinarySentencesStaySeparate() {
        assertEquals(listOf("It rained.", "We stayed in."), joined("It rained.", "We stayed in."))
        assertEquals(listOf("I saw the US.", "Then home."), joined("I saw the US.", "Then home."))
    }

    @Test
    fun trailingSpaceInARangeIsIgnored() {
        val text = "Mr.  Bennet came."
        assertEquals(listOf(0..16), AbbreviationJoiner.join(text, listOf(0..4, 5..16)))
    }

    @Test
    fun emptyAndSingleListsAreReturnedAsIs() {
        assertEquals(emptyList<IntRange>(), AbbreviationJoiner.join("", emptyList()))
        assertEquals(listOf(0..2), AbbreviationJoiner.join("Mr.", listOf(0..2)))
    }
}
