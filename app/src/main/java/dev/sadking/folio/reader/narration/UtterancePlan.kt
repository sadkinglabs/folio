package dev.sadking.folio.reader.narration

/** The pause kind after a unit; [EdgeSilence.gapFrames] turns it into frames. */
internal enum class GapKind {
    FRAGMENT,
    CLAUSE,
    SENTENCE,
    PARAGRAPH,
}

/** One Readium utterance, as the planner predicts Readium will ask for it. */
internal data class PlannedUtterance(
    /** Monotonic within one planner anchor. */
    val index: Long,
    /** normalizeKey(text). */
    val key: String,
    val text: String,
    /** Resource href as a string; the engine keeps the Locator. */
    val href: String,
    /** Same Readium Content element ⇒ same paragraph. */
    val elementOrdinal: Long,
    val endsSentence: Boolean,
    val endsElement: Boolean,
)

/**
 * True when [text], trimmed, ends a sentence: `.!?…` optionally followed by closing quotes or
 * brackets. The planner fills [PlannedUtterance.endsSentence] with this.
 */
internal fun endsSentence(text: String): Boolean = SENTENCE_END.containsMatchIn(text.trimEnd())

private val SENTENCE_END = Regex("[.!?…][\"”’')\\]]*$")

/** What one synthesis call speaks. */
internal data class SynthesisUnit(
    val seq: Long,
    val gen: Long,
    /**
     * ≥ 1. Each entry: an utterance and the [charStart, charEnd) of its own text that this unit
     * speaks. A whole utterance is 0..text.length; a split utterance has one part per unit, and the
     * parts of all its units cover its text exactly, in order.
     */
    val parts: List<UnitPart>,
    val speechText: String,
    val gapAfter: GapKind,
    /** True for a piece of a split utterance that is not its last piece. */
    val continues: Boolean,
) {
    val chars: Int
        get() = speechText.length

    val firstUtterance: Long
        get() = parts.first().utterance.index

    val lastUtterance: Long
        get() = parts.last().utterance.index
}

internal data class UnitPart(val utterance: PlannedUtterance, val charStart: Int, val charEnd: Int)

/**
 * Incremental: push utterances in plan order; units are emitted once decidable. Main-confined.
 *
 * Rules, in order (section 5.4 of docs/READ_ALOUD_PIPELINE.md):
 * 1. fragments of one sentence in one element (italics, links) are joined;
 * 2. a unit under tinyUnitChars joins the next unit of the same element if the result fits
 *    tinyMergeMaxChars;
 * 3. a unit over maxUnitChars is split at `[;:—–]\s`, then `,\s`, then whitespace, into parts of
 *    minSplitChars..maxUnitChars; with no whitespace there, at the last whitespace before
 *    hardSplitChars;
 * 4. the first unit after [restart] is split at its first clause break at ≥ firstUnitMinChars when
 *    it is longer than firstUnitMaxChars, so the first sound comes sooner.
 *
 * Fragments that reach hardSplitChars without ending a sentence are closed there (gap FRAGMENT), so
 * a run-on of many links is never held back indefinitely.
 */
internal class UnitBuilder(private val tuning: NarrationTuning = NarrationTuning.DEFAULT) {
    private var gen = 0L
    private var first = true
    private val group = ArrayList<PlannedUtterance>()
    private var held: Draft? = null

    var nextSeq: Long = 0
        private set

    /** Starts a fresh plan at [gen]; the next unit gets the first-unit cap. */
    fun restart(gen: Long, firstSeq: Long = 0) {
        this.gen = gen
        nextSeq = firstSeq
        first = true
        group.clear()
        held = null
    }

    fun push(utterance: PlannedUtterance): List<SynthesisUnit> {
        val out = ArrayList<SynthesisUnit>()
        val last = group.lastOrNull()
        if (last != null && last.elementOrdinal != utterance.elementOrdinal) {
            closeGroup(out, endedElement = true)
        }
        group += utterance
        if (utterance.endsSentence || utterance.endsElement) {
            closeGroup(out, endedElement = utterance.endsElement)
        } else if (group.sumOf { it.text.length } >= tuning.hardSplitChars) {
            // A run-on without a full stop (many links, a list): stop holding it back.
            closeGroup(out, endedElement = false)
        }
        return out
    }

    /** Emits what is still held (end of book). */
    fun flush(): List<SynthesisUnit> {
        val out = ArrayList<SynthesisUnit>()
        if (group.isNotEmpty()) closeGroup(out, endedElement = true)
        held?.let {
            held = null
            emit(it, out)
        }
        return out
    }

    private fun closeGroup(out: MutableList<SynthesisUnit>, endedElement: Boolean) {
        val utterances = group.toList()
        group.clear()
        val gap =
            when {
                endedElement || utterances.last().endsElement -> GapKind.PARAGRAPH
                utterances.last().endsSentence -> GapKind.SENTENCE
                else -> GapKind.FRAGMENT
            }
        var draft = Draft.of(utterances, gap)
        held?.let { tiny ->
            held = null
            val merged = tiny.mergedWith(draft)
            if (tiny.element == draft.firstElement && merged.length <= tuning.tinyMergeMaxChars) {
                draft = merged
            } else {
                emit(tiny, out)
            }
        }
        if (draft.length < tuning.tinyUnitChars && draft.gap != GapKind.PARAGRAPH) {
            held = draft
        } else {
            emit(draft, out)
        }
    }

    private fun emit(draft: Draft, out: MutableList<SynthesisUnit>) {
        val cuts = longSplit(draft.source)
        // Piece boundaries partition the whole source, so every character belongs to one piece.
        val bounds =
            ArrayList<Int>().apply {
                add(0)
                addAll(cuts)
                add(draft.source.length)
            }
        if (first) {
            first = false
            firstCut(draft.source, bounds[0], bounds[1])?.let { bounds.add(1, it) }
        }
        val carry = HashMap<Int, Int>()
        for (k in 0 until bounds.size - 1) {
            val last = k == bounds.size - 2
            out += unit(draft, bounds[k], bounds[k + 1], last, carry)
        }
    }

    private fun unit(
        draft: Draft,
        start: Int,
        end: Int,
        last: Boolean,
        carry: MutableMap<Int, Int>,
    ): SynthesisUnit {
        val parts = ArrayList<UnitPart>()
        for ((i, seg) in draft.segments.withIndex()) {
            val lo = maxOf(start, seg.start)
            val hi = minOf(end, seg.end)
            if (lo >= hi) continue
            if (draft.source.substring(lo, hi).isBlank()) {
                // Only whitespace of this utterance falls here; give it to its next piece.
                carry.putIfAbsent(i, lo)
                continue
            }
            val from = carry.remove(i) ?: lo
            parts += UnitPart(seg.utterance, from - seg.start, hi - seg.start)
        }
        val lastPart = parts.last()
        return SynthesisUnit(
            seq = nextSeq++,
            gen = gen,
            parts = parts,
            speechText = SpeechText.forSynthesis(draft.source.substring(start, end)),
            gapAfter = if (last) draft.gap else GapKind.CLAUSE,
            continues = lastPart.charEnd < lastPart.utterance.text.length,
        )
    }

    /** Cut positions (each the first character of a new piece) for a source over the cap. */
    private fun longSplit(source: String): List<Int> {
        val cuts = ArrayList<Int>()
        val end = source.trimEnd().length
        var start = firstNonSpace(source, 0)
        while (end - start > tuning.maxUnitChars) {
            val lo = start + tuning.minSplitChars
            val hi = minOf(start + tuning.maxUnitChars, end - tuning.minSplitChars)
            val space =
                lastBreak(source, lo, hi) { it in STRONG_BREAKS }
                    ?: lastBreak(source, lo, hi) { it == ',' }
                    ?: lastBreak(source, lo, hi) { true }
                    ?: lastBreak(source, start + 1, minOf(start + tuning.hardSplitChars, end - 1)) {
                        true
                    }
            // No whitespace at all: one run of 300+ characters has to be cut somewhere.
            val cut =
                if (space == null) start + tuning.maxUnitChars else firstNonSpace(source, space)
            cuts += cut
            start = cut
        }
        return cuts
    }

    /** The first clause break at ≥ firstUnitMinChars, when the piece is over firstUnitMaxChars. */
    private fun firstCut(source: String, start: Int, end: Int): Int? {
        val from = firstNonSpace(source, start)
        val to = from + source.substring(from, end).trimEnd().length
        if (to - from <= tuning.firstUnitMaxChars) return null
        for (p in from + tuning.firstUnitMinChars until to) {
            if (
                source[p].isWhitespace() && (source[p - 1] in STRONG_BREAKS || source[p - 1] == ',')
            ) {
                val cut = firstNonSpace(source, p)
                if (cut < to) return cut
            }
        }
        return null
    }

    /** Largest whitespace position p in [lo, hi] whose preceding character passes [accept]. */
    private fun lastBreak(source: String, lo: Int, hi: Int, accept: (Char) -> Boolean): Int? {
        var p = minOf(hi, source.length - 1)
        while (p >= lo && p > 0) {
            if (
                source[p].isWhitespace() && !source[p - 1].isWhitespace() && accept(source[p - 1])
            ) {
                return p
            }
            p--
        }
        return null
    }

    private fun firstNonSpace(source: String, from: Int): Int {
        var i = from
        while (i < source.length && source[i].isWhitespace()) i++
        return i
    }

    /** Whole utterances joined into one source text, before any split. */
    private class Draft(val source: String, val segments: List<Segment>, val gap: GapKind) {
        val length: Int
            get() = source.trim().length

        val element: Long
            get() = segments.last().utterance.elementOrdinal

        val firstElement: Long
            get() = segments.first().utterance.elementOrdinal

        fun mergedWith(next: Draft): Draft {
            val sep = separator(source, next.source)
            val shift = source.length + sep.length
            return Draft(
                source + sep + next.source,
                segments +
                    next.segments.map { Segment(it.utterance, it.start + shift, it.end + shift) },
                next.gap,
            )
        }

        companion object {
            fun of(utterances: List<PlannedUtterance>, gap: GapKind): Draft {
                val text = StringBuilder()
                val segments = ArrayList<Segment>(utterances.size)
                for (u in utterances) {
                    text.append(separator(text, u.text))
                    segments += Segment(u, text.length, text.length + u.text.length)
                    text.append(u.text)
                }
                return Draft(text.toString(), segments, gap)
            }
        }
    }

    /** One utterance's place in a draft's source: [start, end). */
    private class Segment(val utterance: PlannedUtterance, val start: Int, val end: Int)

    private companion object {
        const val STRONG_BREAKS = ";:—–"
        const val NO_SPACE_BEFORE = ",.;:!?)]}”’'…"
        const val NO_SPACE_AFTER = "([{“‘"

        /** A space between two fragments unless spacing or punctuation already separates them. */
        fun separator(before: CharSequence, after: String): String {
            val a = before.lastOrNull() ?: return ""
            val b = after.firstOrNull() ?: return ""
            if (a.isWhitespace() || b.isWhitespace()) return ""
            if (b in NO_SPACE_BEFORE || a in NO_SPACE_AFTER) return ""
            return " "
        }
    }
}
