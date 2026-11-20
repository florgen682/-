package app.clipradar.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeakFinderTest {
    @Test
    fun picksFiveHighestPeaksAtLeast60SecondsApart() {
        // Пики на 100, 105 (рядом, ниже), 300, 500, 700, 900, 1100 сек.
        val points = (0 until 1200 step 5).map { t ->
            val v = when (t) {
                100 -> 1.0; 105 -> 0.95; 300 -> 0.9; 500 -> 0.8; 700 -> 0.7; 900 -> 0.6; 1100 -> 0.5
                else -> 0.1
            }
            Point(t.toDouble(), v)
        }
        val peaks = PeakFinder.findTopPeaks(points, count = 5, minDistanceSec = 60.0)
        assertEquals(listOf(100.0, 300.0, 500.0, 700.0, 900.0), peaks.map { it.timeSec })
        // Ни одна пара не ближе 60 секунд.
        for (a in peaks) for (b in peaks) if (a != b) assertTrue(kotlin.math.abs(a.timeSec - b.timeSec) >= 60)
    }

    @Test
    fun returnsFewerWhenNotEnoughDistinctPeaks() {
        val points = listOf(Point(10.0, 1.0), Point(20.0, 0.9), Point(30.0, 0.8))
        val peaks = PeakFinder.findTopPeaks(points, count = 5, minDistanceSec = 60.0)
        assertEquals(1, peaks.size)
        assertEquals(10.0, peaks[0].timeSec)
    }

    @Test
    fun ignoresVeryBeginningWhenAsked() {
        val points = listOf(Point(2.0, 1.0), Point(200.0, 0.5))
        val peaks = PeakFinder.findTopPeaks(points, count = 5, ignoreBeforeSec = 10.0)
        assertEquals(listOf(200.0), peaks.map { it.timeSec })
    }

    @Test
    fun heatmapSegmentsUseMiddleTime() {
        val pts = PeakFinder.heatmapToPoints(listOf(HeatmapSegment(10.0, 20.0, 0.5)))
        assertEquals(15.0, pts[0].timeSec)
    }

    @Test
    fun rangeAroundStaysInsideVideo() {
        assertEquals(TimeRange(80.0, 125.0), PeakFinder.rangeAround(100.0, 20.0, 25.0, 1000.0))
        // У самого начала — сдвигаем вправо, длина сохраняется.
        assertEquals(TimeRange(0.0, 45.0), PeakFinder.rangeAround(5.0, 20.0, 25.0, 1000.0))
        // У самого конца — сдвигаем влево.
        assertEquals(TimeRange(955.0, 1000.0), PeakFinder.rangeAround(995.0, 20.0, 25.0, 1000.0))
    }

    @Test
    fun loudestPlacesAreFound() {
        val db = MutableList(600) { -30.0 }
        db[100] = -5.0; db[101] = -5.0; db[102] = -5.0
        db[400] = -10.0; db[401] = -10.0
        val peaks = Loudness.loudestPeaks(db, count = 5)
        assertEquals(101.5, peaks[0].timeSec)
        assertTrue(peaks.any { it.timeSec in 399.0..402.0 })
        val jump = Loudness.jumpDb(db, 101.0)!!
        assertTrue(jump > 20.0, "jump=$jump")
    }

    @Test
    fun loudnessMeterProducesOneValuePerSecond() {
        val meter = LoudnessMeter(sampleRate = 100, channels = 1)
        repeat(100) { meter.feed(0.0f) }      // тишина
        repeat(100) { meter.feed(0.5f) }      // громко
        val db = meter.finish()
        assertEquals(2, db.size)
        assertTrue(db[0] < -100)
        assertEquals(-6.0, db[1], 0.1)
    }
}
