package app.clipradar.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentBufferTest {
    private fun seg(seq: Long) = NewSegment(seq, "s$seq.ts", 2_000)

    @Test
    fun firstBatchEndsAtArrivalTime() {
        val ring = SegmentRing()
        val added = ring.addBatch(listOf(seg(1), seg(2), seg(3)), arrivalMs = 100_000)
        assertEquals(listOf(94_000L, 96_000L, 98_000L), added.map { it.startMs })
        assertEquals(100_000, added.last().endMs)
    }

    @Test
    fun nextSegmentsContinueWithoutGaps() {
        val ring = SegmentRing()
        ring.addBatch(listOf(seg(1), seg(2)), 10_000)
        val added = ring.addBatch(listOf(seg(3)), 12_700) // пришёл с опозданием 0.7 сек
        assertEquals(10_000, added[0].startMs)
        // Повторно тот же кусок не добавляется.
        assertTrue(ring.addBatch(listOf(seg(3)), 13_000).isEmpty())
    }

    @Test
    fun bigGapStartsNewTimeline() {
        val ring = SegmentRing()
        ring.addBatch(listOf(seg(1)), 10_000)
        val added = ring.addBatch(listOf(seg(50)), 100_000)
        assertEquals(98_000, added[0].startMs)
    }

    @Test
    fun ringKeepsOnlyLastThreeMinutes() {
        val ring = SegmentRing(keepMs = 180_000)
        for (i in 1..150L) ring.addBatch(listOf(seg(i)), i * 2_000)
        val removed = ring.prune(nowMs = 300_000)
        assertTrue(removed.isNotEmpty())
        assertTrue(ring.all().all { it.endMs >= 120_000 })
        assertEquals(150L, ring.all().last().sequence)
    }

    @Test
    fun selectsSegments40BeforeAnd15After() {
        val ring = SegmentRing()
        for (i in 1..100L) ring.addBatch(listOf(seg(i)), i * 2_000) // кусок i: [(i-1)*2, i*2] сек
        val plan = LiveLogic.planClip(ring.all(), momentMs = 120_000, beforeMs = 40_000, afterMs = 15_000)
        assertNotNull(plan)
        assertEquals(80_000, plan.sourceStartMs)
        assertEquals(40.0, plan.momentOffsetSec, 1e-9)
        assertEquals(28, plan.segments.size) // 80..136 сек = 28 кусков по 2 сек
        assertEquals(56.0, plan.durationSec, 1e-9)
        assertTrue(plan.segments.zipWithNext().all { (a, b) -> b.sequence == a.sequence + 1 })
    }

    @Test
    fun partialBufferStillGivesClip() {
        val ring = SegmentRing()
        for (i in 1..10L) ring.addBatch(listOf(seg(i)), 100_000 + i * 2_000)
        val plan = LiveLogic.planClip(ring.all(), momentMs = 110_000, beforeMs = 40_000, afterMs = 15_000)!!
        assertEquals(100_000, plan.sourceStartMs)
        assertEquals(10.0, plan.momentOffsetSec, 1e-9)
    }

    @Test
    fun emptySelectionReturnsNull() {
        assertNull(LiveLogic.planClip(emptyList(), 1000, 40_000, 15_000))
    }

    @Test
    fun cooldownBlocksMomentsForPause() {
        val c = Cooldown(90_000)
        assertTrue(c.tryAcquire(0))
        assertTrue(!c.tryAcquire(60_000))
        assertTrue(c.tryAcquire(90_000))
    }
}
