package dev.sadking.folio.reader.narration

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sample rate 1000, so frames are milliseconds of listening. */
class PcmQueueTest {
    private val rate = 1000

    /** A unit whose spans are [utterances] (index to speech frames) laid end to end. */
    private fun unit(
        seq: Long,
        vararg utterances: Pair<Long, Int>,
        gen: Long = 0,
        gap: Int = 0,
        continues: Boolean = false,
    ): ReadyUnit {
        var at = 0
        val spans = utterances.map { (index, frames) ->
            Span(index, "k$index", at, at + frames).also { at += frames }
        }
        return ReadyUnit(
            seq = seq,
            gen = gen,
            firstUtterance = spans.first().utteranceIndex,
            lastUtterance = spans.last().utteranceIndex,
            pcm = ShortArray(at) { 1000 },
            gapFrames = gap,
            spans = spans,
            cacheKey = "key$seq",
            cacheHit = false,
            continues = continues,
        )
    }

    private fun simple(seq: Long, frames: Int = 1000, gen: Long = 0) =
        unit(seq, seq to frames, gen = gen)

    private fun PcmQueue.next(
        max: Long = Long.MAX_VALUE,
        request: Long = Long.MAX_VALUE,
        past: Long = Long.MAX_VALUE,
    ) = nextToWrite(max, request, past)

    /** Writes the next unit whole; returns its seq, or null when nothing may be written. */
    private fun PcmQueue.writeOne(
        max: Long = Long.MAX_VALUE,
        request: Long = Long.MAX_VALUE,
        past: Long = Long.MAX_VALUE,
    ): Long? {
        val item = next(max, request, past) ?: return null
        markWritten(item.seq, item.count + item.gapRemaining)
        return item.seq
    }

    private fun started(i: Long) = HeardEvent.Started(0, i)

    private fun done(i: Long) = HeardEvent.Done(0, i)

    @Test
    fun offerRefusesStaleGensAndDuplicates() {
        val queue = PcmQueue(rate)
        assertFalse(queue.offer(simple(0, gen = 1), currentGen = 2))
        assertTrue(queue.offer(simple(0, gen = 2), currentGen = 2))
        assertFalse(queue.offer(simple(0, gen = 2), currentGen = 2))
        assertFalse("an older gen than one seen", queue.offer(simple(1, gen = 1), currentGen = 1))
    }

    @Test
    fun offerAcceptsAnEarlierUnitOnlyUntilALaterOneIsWritten() {
        val queue = PcmQueue(rate)
        assertTrue(queue.offer(simple(1), 0))
        assertTrue("nothing written yet", queue.offer(simple(0), 0))
        assertEquals(0L, queue.writeOne())
        assertEquals(1L, queue.writeOne())

        val other = PcmQueue(rate)
        assertTrue(other.offer(simple(5), 0))
        assertEquals(5L, other.writeOne())
        assertFalse("behind a written unit", other.offer(simple(4), 0))
        assertTrue(other.offer(simple(6), 0))
    }

    @Test
    fun reserveCountsOnlyContiguousFrames() {
        val queue = PcmQueue(rate)
        queue.offer(simple(0), 0)
        queue.offer(simple(1), 0)
        queue.offer(simple(3), 0)
        assertEquals(2.0, queue.reserveSeconds(0), 1e-9)
        queue.offer(simple(2), 0)
        assertEquals(4.0, queue.reserveSeconds(0), 1e-9)
        queue.writeOne()
        // 600 written-unheard frames of unit 0, then units 1..3 ready.
        assertEquals(3.6, queue.reserveSeconds(400), 1e-9)
    }

    @Test
    fun reserveIsInListeningSecondsPerFrame() {
        val queue = PcmQueue(rate, listeningSecondsPerFrame = 0.5 / rate)
        queue.offer(simple(0), 0)
        assertEquals(0.5, queue.reserveSeconds(0), 1e-9)
    }

    @Test
    fun nothingIsWrittenBeyondTheUtteranceLimit() {
        val queue = PcmQueue(rate)
        (0L..2L).forEach { queue.offer(simple(it), 0) }
        assertEquals(0L, queue.writeOne(max = 1, request = 0))
        assertEquals(1L, queue.writeOne(max = 1, request = 0))
        assertNull(queue.next(max = 1, request = 0))
        assertEquals(2L, queue.writeOne(max = 2, request = 0))
    }

    @Test
    fun nothingStartsTooFarPastTheRequestedUtterance() {
        val queue = PcmQueue(rate)
        (0L..3L).forEach { queue.offer(simple(it), 0) }
        assertEquals(0L, queue.writeOne(request = 0, past = 1500))
        assertEquals(1L, queue.writeOne(request = 0, past = 1500))
        assertEquals(2L, queue.writeOne(request = 0, past = 1500))
        assertNull("starts 2000 frames past the request", queue.next(request = 0, past = 1500))
        assertEquals(3L, queue.writeOne(request = 1, past = 1500))
    }

    @Test
    fun aUnitPastARequestThatIsHeldNowhereWaits() {
        val queue = PcmQueue(rate)
        queue.offer(unit(0, 2L to 1000), 0)
        queue.offer(unit(1, 4L to 1000), 0)
        queue.writeOne()
        queue.onHead(1000)
        // Utterance 3 is missing (say its synthesis failed): nothing past it may start.
        assertNull(queue.next(request = 3, past = 3000))
        assertNotNull(queue.next(request = 4, past = 3000))
    }

    @Test
    fun aRequestOlderThanAnythingHeldDoesNotBlockWriting() {
        val queue = PcmQueue(rate)
        queue.offer(simple(4), 0)
        assertNotNull(queue.next(max = 6, request = 3, past = 3000))
        assertNull("the utterance limit still holds", queue.next(max = 3, request = 3, past = 3000))
    }

    @Test
    fun partialWritesAdvanceThroughSpeechThenTheGap() {
        val queue = PcmQueue(rate)
        queue.offer(unit(0, 0L to 100, gap = 50), 0)
        val first = queue.next()!!
        assertEquals(listOf(0, 100, 50), listOf(first.offset, first.count, first.gapRemaining))
        queue.markWritten(0, 60)
        val second = queue.next()!!
        assertEquals(listOf(60, 40, 50), listOf(second.offset, second.count, second.gapRemaining))
        queue.markWritten(0, 70)
        val third = queue.next()!!
        assertEquals(listOf(100, 0, 20), listOf(third.offset, third.count, third.gapRemaining))
        queue.markWritten(0, 20)
        assertNull(queue.next())
        assertEquals(150L, queue.writtenFrames)
        queue.markWritten(0, 99)
        assertEquals("over-reporting is ignored", 150L, queue.writtenFrames)
    }

    @Test
    fun aWriteForAnotherUnitIsIgnored() {
        val queue = PcmQueue(rate)
        queue.offer(simple(0), 0)
        queue.markWritten(7, 500)
        assertEquals(0L, queue.writtenFrames)
    }

    @Test
    fun headEventsComeOnceEachAndInOrder() {
        val queue = PcmQueue(rate)
        queue.offer(unit(0, 5L to 400, 6L to 600, gap = 200), 0)
        queue.writeOne()
        assertEquals(emptyList<HeardEvent>(), queue.onHead(0))
        assertEquals(listOf(started(5)), queue.onHead(1))
        assertEquals(listOf(done(5)), queue.onHead(400))
        assertEquals(listOf(started(6)), queue.onHead(401))
        assertEquals(listOf(done(6)), queue.onHead(1000))
        assertEquals(emptyList<HeardEvent>(), queue.onHead(1000))
        assertEquals(0, queue.snapshot(1000).recentUnits)
        assertEquals("heard with its gap", emptyList<HeardEvent>(), queue.onHead(1200))
        assertEquals(1, queue.snapshot(1200).recentUnits)
    }

    @Test
    fun aBigHeadJumpGivesEveryEventInOrder() {
        val queue = PcmQueue(rate)
        queue.offer(unit(0, 1L to 300, 2L to 300), 0)
        queue.offer(unit(1, 3L to 300), 0)
        queue.writeOne()
        queue.writeOne()
        assertEquals(
            listOf(started(1), done(1), started(2), done(2), started(3), done(3)),
            queue.onHead(5000),
        )
    }

    @Test
    fun theHeadNeverRunsPastWhatWasWritten() {
        val queue = PcmQueue(rate)
        queue.offer(simple(0), 0)
        queue.offer(simple(1), 0)
        queue.writeOne()
        assertEquals(listOf(started(0), done(0)), queue.onHead(10_000))
        queue.writeOne()
        assertEquals(listOf(started(1)), queue.onHead(1500))
    }

    @Test
    fun aSplitUtteranceIsDoneOnlyAfterItsLastPiece() {
        val queue = PcmQueue(rate)
        queue.offer(unit(0, 7L to 500, continues = true), 0)
        queue.offer(unit(1, 7L to 500, 8L to 500), 0)
        queue.writeOne()
        queue.writeOne()
        assertEquals(listOf(started(7)), queue.onHead(600))
        assertEquals(listOf(done(7), started(8), done(8)), queue.onHead(1500))
    }

    @Test
    fun flushReturnsWrittenUnheardUnitsToReady() {
        val queue = PcmQueue(rate)
        queue.offer(simple(0), 0)
        queue.offer(simple(1), 0)
        queue.writeOne()
        queue.writeOne()
        assertEquals(listOf(started(0)), queue.onHead(500))
        queue.onFlush()
        assertEquals(0L, queue.writtenFrames)
        assertEquals(2.0, queue.reserveSeconds(0), 1e-9)
        val item = queue.next()!!
        assertEquals(0L, item.seq)
        assertEquals(0, item.offset)
        queue.markWritten(0, 1000)
        assertEquals("events again after a flush", listOf(started(0)), queue.onHead(1))
    }

    @Test
    fun rewindToAReadyUtteranceSkipsWhatComesBefore() {
        val queue = PcmQueue(rate)
        (0L..2L).forEach { queue.offer(simple(it), 0) }
        assertTrue(queue.rewindTo(1))
        assertEquals(1L, queue.next()!!.seq)
        assertEquals(1, queue.snapshot(0).recentUnits)
        assertTrue(queue.hasUtterance(0))
        assertFalse(queue.isReady(0))
    }

    @Test
    fun rewindToAWrittenUtteranceAfterAFlush() {
        val queue = PcmQueue(rate)
        (0L..2L).forEach { queue.offer(simple(it), 0) }
        repeat(3) { queue.writeOne() }
        queue.onHead(300)
        queue.onFlush()
        assertTrue(queue.rewindTo(2))
        assertEquals(2L, queue.writeOne())
        assertEquals(listOf(started(2), done(2)), queue.onHead(1000))
    }

    @Test
    fun rewindToAHeardUtteranceComesBackFromTheRing() {
        val queue = PcmQueue(rate)
        (0L..2L).forEach { queue.offer(simple(it), 0) }
        repeat(3) { queue.writeOne() }
        queue.onHead(2000)
        assertEquals(2, queue.snapshot(2000).recentUnits)
        queue.onFlush()
        assertTrue(queue.rewindTo(0))
        assertEquals(0, queue.snapshot(0).recentUnits)
        assertEquals(listOf(0L, 1L, 2L), List(3) { queue.writeOne() })
        assertEquals(3.0, queue.reserveSeconds(0), 1e-9)
    }

    @Test
    fun rewindIntoAMergedUnitStartsAtThatUtterance() {
        val queue = PcmQueue(rate)
        queue.offer(unit(0, 3L to 400, 4L to 600), 0)
        assertTrue(queue.rewindTo(4))
        val item = queue.next()!!
        assertEquals(400, item.offset)
        assertEquals(600, item.count)
        queue.markWritten(0, 600)
        assertEquals(listOf(started(4), done(4)), queue.onHead(600))
        assertEquals(1, queue.snapshot(600).recentUnits)
    }

    @Test
    fun rewindToSomethingNoLongerHeldFails() {
        val queue = PcmQueue(rate)
        queue.offer(simple(0), 0)
        assertFalse(queue.rewindTo(9))
        assertEquals(0L, queue.next()!!.seq)
    }

    @Test
    fun theRingKeepsThreeUnitsAndThirtySeconds() {
        val queue = PcmQueue(rate)
        (0L..4L).forEach { queue.offer(simple(it), 0) }
        repeat(5) { queue.writeOne() }
        queue.onHead(5000)
        assertEquals(3, queue.snapshot(5000).recentUnits)
        assertFalse(queue.hasUtterance(1))
        assertTrue(queue.hasUtterance(2))

        val short = PcmQueue(rate, NarrationTuning(recentRingSeconds = 2.5))
        (0L..4L).forEach { short.offer(simple(it), 0) }
        repeat(5) { short.writeOne() }
        short.onHead(5000)
        assertEquals(2, short.snapshot(5000).recentUnits)

        val long = PcmQueue(rate, NarrationTuning(recentRingSeconds = 0.5))
        long.offer(simple(0), 0)
        long.writeOne()
        long.onHead(1000)
        assertEquals(
            "the last heard unit stays even when longer",
            1,
            long.snapshot(1000).recentUnits,
        )
    }

    @Test
    fun invalidateKeepsOnlyTheCurrentGen() {
        val queue = PcmQueue(rate)
        queue.offer(simple(0, gen = 1), 1)
        queue.offer(simple(1, gen = 1), 1)
        queue.writeOne()
        queue.onHead(1000)
        queue.invalidate(2)
        assertFalse(queue.isReady(1))
        assertFalse(queue.hasUtterance(0))
        assertEquals(0, queue.snapshot(1000).recentUnits)
        assertTrue(queue.offer(simple(0, gen = 2), 2))
        assertEquals(0L, queue.next()!!.seq)
        queue.invalidate(1)
        assertTrue("an older invalidate is ignored", queue.isReady(0))
        assertFalse(queue.offer(simple(1, gen = 1), 1))
    }

    @Test
    fun invalidatingTheSameGenKeepsTheWriteOrder() {
        val queue = PcmQueue(rate)
        queue.offer(simple(0), 0)
        queue.writeOne()
        queue.onHead(1000)
        queue.invalidate(0)
        queue.offer(simple(2), 0)
        assertNull("unit 1 is still owed", queue.next())
        queue.offer(simple(1), 0)
        assertEquals(1L, queue.writeOne())
    }

    @Test
    fun offeringANewGenDropsTheOldOne() {
        val queue = PcmQueue(rate)
        queue.offer(simple(0, gen = 1), 1)
        assertTrue(queue.offer(simple(0, gen = 2), 2))
        assertEquals(1.0, queue.reserveSeconds(0), 1e-9)
    }

    @Test
    fun readinessAndSnapshot() {
        val queue = PcmQueue(rate)
        queue.offer(unit(0, 0L to 1000, gap = 500), 0)
        queue.offer(simple(1), 0)
        queue.offer(simple(3), 0)
        assertTrue(queue.isReady(1))
        assertFalse(queue.isReady(2))
        assertTrue(queue.hasUtterance(3))
        queue.writeOne()
        val snapshot = queue.snapshot(500)
        assertEquals(2, snapshot.readyUnits)
        assertEquals(2.0, snapshot.readySeconds, 1e-9)
        assertEquals(1.0, snapshot.writtenUnheardSeconds, 1e-9)
        assertEquals(2.0, snapshot.reserveSeconds, 1e-9)
        assertEquals(1L, snapshot.nextReadySeq)
        assertEquals(0, snapshot.recentUnits)
    }

    @Test(timeout = 30_000)
    fun writerProducerAndHeadThreadsKeepTheInvariants() {
        val queue = PcmQueue(rate)
        val count = 400L
        val total = (0 until count).sumOf { 50L + it % 7 * 10 + 5 }
        val errors = Collections.synchronizedList(ArrayList<Throwable>())
        val events = Collections.synchronizedList(ArrayList<HeardEvent>())
        val done = CountDownLatch(3)
        fun guarded(block: () -> Unit) = thread {
            try {
                block()
            } catch (t: Throwable) {
                errors += t
            } finally {
                done.countDown()
            }
        }
        guarded {
            for (seq in 0 until count) {
                check(queue.offer(unit(seq, seq to 50 + (seq % 7 * 10).toInt(), gap = 5), 0))
                if (seq % 16 == 0L) Thread.yield()
            }
        }
        guarded {
            val random = Random(1)
            while (queue.writtenFrames < total) {
                val item = queue.nextToWrite(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
                if (item == null) {
                    Thread.yield()
                    continue
                }
                val room = item.count + item.gapRemaining
                queue.markWritten(item.seq, random.nextInt(1, room + 1))
            }
        }
        guarded {
            var head = 0L
            while (head < total) {
                head = minOf(total, maxOf(head, queue.writtenFrames - 3))
                check(queue.reserveSeconds(head) >= 0.0)
                events += queue.onHead(head)
                if (queue.writtenFrames >= total) head = total
            }
            events += queue.onHead(total)
        }
        assertTrue(done.await(25, TimeUnit.SECONDS))
        assertEquals(emptyList<Throwable>(), errors)
        assertEquals(total, queue.writtenFrames)
        val expected = (0 until count).flatMap { listOf(started(it), done(it)) }
        assertEquals(expected, events)
        assertEquals(0.0, queue.reserveSeconds(total), 1e-9)
    }
}
