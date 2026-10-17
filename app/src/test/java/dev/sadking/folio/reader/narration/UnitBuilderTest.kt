package dev.sadking.folio.reader.narration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnitBuilderTest {
    /** No first-unit cap, for tests about the other rules. */
    private val uncapped = NarrationTuning(firstUnitMaxChars = 10_000)

    /** Builds utterances paragraph by paragraph; the last utterance of each ends its element. */
    private class Plan {
        val utterances = ArrayList<PlannedUtterance>()
        private var element = 0L

        fun paragraph(vararg texts: String): Plan {
            texts.forEachIndexed { i, text ->
                utterances +=
                    PlannedUtterance(
                        index = utterances.size.toLong(),
                        key = normalizeKey(text),
                        text = text,
                        href = "ch1.xhtml",
                        elementOrdinal = element,
                        endsSentence = endsSentence(text),
                        endsElement = i == texts.lastIndex,
                    )
            }
            element++
            return this
        }
    }

    private fun build(
        plan: Plan,
        tuning: NarrationTuning = uncapped,
        gen: Long = 1,
        firstSeq: Long = 0,
    ): List<SynthesisUnit> {
        val builder = UnitBuilder(tuning)
        builder.restart(gen, firstSeq)
        val out = ArrayList<SynthesisUnit>()
        plan.utterances.forEach { out += builder.push(it) }
        out += builder.flush()
        return out
    }

    /** Every utterance's parts, across units, cover its text from 0 to its length with no hole. */
    private fun assertCoverage(plan: Plan, units: List<SynthesisUnit>) {
        val parts = units.flatMap { it.parts }.groupBy { it.utterance.index }
        for (u in plan.utterances) {
            val mine = parts[u.index] ?: error("utterance ${u.index} missing")
            assertEquals("start of ${u.index}", 0, mine.first().charStart)
            assertEquals("end of ${u.index}", u.text.length, mine.last().charEnd)
            mine.zipWithNext { a, b -> assertEquals("hole in ${u.index}", a.charEnd, b.charStart) }
        }
        val order = units.flatMap { unit -> unit.parts.map { it.utterance.index } }.distinct()
        assertEquals(plan.utterances.map { it.index }, order)
    }

    private fun words(count: Int, word: String = "word") = List(count) { word }.joinToString(" ")

    @Test
    fun fragmentsOfOneSentenceAreJoined() {
        val plan =
            Plan()
                .paragraph("He was ", "very", " tired of waiting for the train to arrive at last.")
        val units = build(plan)
        assertEquals(1, units.size)
        assertEquals(
            "He was very tired of waiting for the train to arrive at last.",
            units[0].speechText,
        )
        assertEquals(3, units[0].parts.size)
        assertEquals(GapKind.PARAGRAPH, units[0].gapAfter)
        assertCoverage(plan, units)
    }

    @Test
    fun trimmedFragmentsGetASpaceButNotBeforePunctuation() {
        val plan =
            Plan().paragraph("He read", "The Times", ", and then he slept for a very long while.")
        assertEquals(
            "He read The Times, and then he slept for a very long while.",
            build(plan)[0].speechText,
        )
    }

    @Test
    fun separateSentencesStaySeparate() {
        val plan =
            Plan()
                .paragraph(
                    "The rain had stopped by the time we reached the station.",
                    "Nobody was waiting on the platform for us.",
                )
        val units = build(plan)
        assertEquals(2, units.size)
        assertEquals(GapKind.SENTENCE, units[0].gapAfter)
        assertEquals(GapKind.PARAGRAPH, units[1].gapAfter)
        assertEquals(listOf(0L, 1L), units.map { it.seq })
    }

    @Test
    fun aTinySentenceJoinsTheNextInTheSameParagraph() {
        val plan =
            Plan().paragraph("Yes.", "He looked out of the window at the empty street below.")
        val units = build(plan)
        assertEquals(1, units.size)
        assertEquals(
            "Yes. He looked out of the window at the empty street below.",
            units[0].speechText,
        )
        assertEquals(2, units[0].parts.size)
        assertCoverage(plan, units)
    }

    @Test
    fun aTinySentenceDoesNotJoinAcrossParagraphs() {
        val plan =
            Plan()
                .paragraph("Yes.")
                .paragraph("He looked out of the window at the empty street below.")
        val units = build(plan)
        assertEquals(
            listOf("Yes.", "He looked out of the window at the empty street below."),
            units.map { it.speechText },
        )
        assertEquals(GapKind.PARAGRAPH, units[0].gapAfter)
    }

    @Test
    fun aTinyMergeStaysWithinTheMergeLimit() {
        val long = words(40) + "." // 199 chars
        val plan = Plan().paragraph("Yes.", long)
        val units = build(plan)
        assertEquals(2, units.size)
        assertEquals("Yes.", units[0].speechText)
        assertEquals(GapKind.SENTENCE, units[0].gapAfter)
    }

    @Test
    fun severalTinySentencesJoinUntilTheyAreLongEnough() {
        val plan =
            Plan().paragraph("No.", "Yes.", "Maybe.", "Perhaps we should go home now, said she.")
        val units = build(plan)
        assertEquals(1, units.size)
        assertEquals(
            "No. Yes. Maybe. Perhaps we should go home now, said she.",
            units[0].speechText,
        )
    }

    @Test
    fun aLongSentenceSplitsAtASemicolonFirst() {
        val text = words(30) + ", " + words(10) + "; " + words(30) + ", " + words(10) + "."
        val plan = Plan().paragraph(text)
        val units = build(plan)
        assertEquals(2, units.size)
        assertTrue(units[0].speechText.endsWith(";"))
        assertEquals(GapKind.CLAUSE, units[0].gapAfter)
        assertTrue(units[0].continues)
        assertFalse(units[1].continues)
        assertEquals(GapKind.PARAGRAPH, units[1].gapAfter)
        units.forEach { assertTrue(it.chars in 80..300) }
        assertCoverage(plan, units)
    }

    @Test
    fun withoutASemicolonALongSentenceSplitsAtACommaThenAtASpace() {
        val commas = words(40) + ", " + words(40) + ", " + words(20) + "."
        val byComma = build(Plan().paragraph(commas))
        assertTrue(byComma[0].speechText.endsWith(","))
        byComma.forEach { assertTrue(it.chars in 80..300) }

        val plain = words(100) + "."
        val bySpace = build(Plan().paragraph(plain))
        assertTrue(bySpace.size >= 2)
        bySpace.forEach {
            assertTrue(it.chars in 80..300)
            assertTrue(it.speechText.split(" ").all { w -> w == "word" || w == "word." })
        }
    }

    @Test
    fun withNoSpaceInRangeTheHardSplitFallsOnTheLastSpaceBefore400() {
        val text = "a".repeat(350) + " " + "b".repeat(100) + "."
        val units = build(Plan().paragraph(text))
        assertEquals(listOf("a".repeat(350), "b".repeat(100) + "."), units.map { it.speechText })
    }

    @Test
    fun theFirstUnitAfterRestartIsCutAtItsFirstClauseBreak() {
        val sentence = words(10) + ", " + words(10) + ", " + words(10) + "."
        val plan = Plan().paragraph(sentence, sentence)
        val units = build(plan, NarrationTuning.DEFAULT)
        assertEquals(3, units.size)
        assertEquals(words(10) + ",", units[0].speechText)
        assertEquals(GapKind.CLAUSE, units[0].gapAfter)
        assertTrue(units[0].continues)
        assertEquals(words(10) + ", " + words(10) + ".", units[1].speechText)
        assertEquals(sentence, units[2].speechText)
        assertCoverage(plan, units)
    }

    @Test
    fun restartAppliesTheCapAgainWithNewGenAndSeq() {
        val sentence = words(10) + ", " + words(20) + "."
        val builder = UnitBuilder()
        builder.restart(gen = 3)
        val first = builder.push(Plan().paragraph(sentence).utterances[0])
        assertEquals(2, first.size)
        builder.restart(gen = 4, firstSeq = 100)
        val again = builder.push(Plan().paragraph(sentence).utterances[0])
        assertEquals(2, again.size)
        assertEquals(listOf(100L, 101L), again.map { it.seq })
        assertTrue(again.all { it.gen == 4L })
        assertEquals(102L, builder.nextSeq)
    }

    @Test
    fun aRunOnWithoutAFullStopIsNotHeldBackForever() {
        val builder = UnitBuilder(uncapped)
        builder.restart(1)
        val pieces = List(20) { "and then a link to page $it" }
        val plan = Plan().paragraph(*pieces.toTypedArray(), "the end.")
        val emitted = plan.utterances.take(pieces.size).flatMap { builder.push(it) }
        assertTrue(emitted.isNotEmpty())
        assertEquals(GapKind.FRAGMENT, emitted.last().gapAfter)
    }

    @Test
    fun aHeldTinyUnitComesOutOnFlush() {
        val builder = UnitBuilder(uncapped)
        builder.restart(1)
        val plan = Plan().paragraph("Yes.", "and more")
        assertTrue(builder.push(plan.utterances[0]).isEmpty())
        val rest = builder.flush()
        assertEquals(listOf("Yes."), rest.map { it.speechText })
        assertTrue(builder.flush().isEmpty())
    }

    @Test
    fun speechTextIsWhatKokoroHearsAndPartsUseTheOriginal() {
        val text = "Mr. Darcy bowed to everyone in the room before he left."
        val units = build(Plan().paragraph(text))
        assertEquals(
            "Mister Darcy bowed to everyone in the room before he left.",
            units[0].speechText,
        )
        assertEquals(UnitPart(units[0].parts[0].utterance, 0, text.length), units[0].parts[0])
    }

    @Test
    fun partsCoverEveryUtteranceExactlyAcrossAMixedChapter() {
        val plan =
            Plan()
                .paragraph("Chapter 1")
                .paragraph(
                    "It is a truth ",
                    "universally",
                    " acknowledged, that a single man in possession of a good fortune, must be in want of a wife.",
                )
                .paragraph(
                    "“My dear Mr. Bennet,” said his lady to him one day, “have you heard that Netherfield Park is let at last?”",
                    "Mr. Bennet replied that he had not.",
                )
                .paragraph("No.", words(70, "long") + "; " + words(30, "tail") + ".")
                .paragraph(" spaced fragment ", " with spaces ", "at the edges.")
        val units = build(plan, NarrationTuning.DEFAULT, gen = 9, firstSeq = 5)
        assertCoverage(plan, units)
        assertEquals((5L until 5L + units.size).toList(), units.map { it.seq })
        assertTrue(units.all { it.gen == 9L })
        assertTrue(units.all { it.chars <= 300 })
        assertTrue(units.all { it.speechText.isNotBlank() })
    }

    @Test
    fun endsSentenceRecognisesClosingQuotes() {
        assertTrue(endsSentence("He left."))
        assertTrue(endsSentence("“Go!” "))
        assertTrue(endsSentence("Really?)"))
        assertTrue(endsSentence("And so…"))
        assertFalse(endsSentence("He was "))
        assertFalse(endsSentence("said she,"))
    }
}
