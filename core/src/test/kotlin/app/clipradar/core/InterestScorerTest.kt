package app.clipradar.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterestScorerTest {
    @Test
    fun strongMomentScoresHigh() {
        val s = InterestScorer.score(MomentSignals(chatRatio = 7.0, hypeShare = 0.6, uniqueChatters = 40, audioJumpDb = 12.0))
        assertEquals(100, s.score)
        assertTrue(s.reason.contains("чат"))
    }

    @Test
    fun weakMomentScoresLow() {
        val s = InterestScorer.score(MomentSignals(chatRatio = 3.0, hypeShare = 0.05, uniqueChatters = 5, audioJumpDb = 1.0))
        assertTrue(s.score < 60, "score=${s.score}")
    }

    @Test
    fun scoreAlwaysBetween0And100() {
        for (r in listOf(0.0, 1.0, 3.0, 100.0)) for (h in listOf(0.0, 0.5, 1.0)) for (u in listOf(0, 10, 1000)) {
            val s = InterestScorer.score(MomentSignals(r, h, u, -5.0)).score
            assertTrue(s in 0..100)
        }
    }

    @Test
    fun moreChatMeansHigherScore() {
        val a = InterestScorer.score(MomentSignals(chatRatio = 3.0, hypeShare = 0.2, uniqueChatters = 10)).score
        val b = InterestScorer.score(MomentSignals(chatRatio = 5.0, hypeShare = 0.2, uniqueChatters = 10)).score
        val c = InterestScorer.score(MomentSignals(chatRatio = 5.0, hypeShare = 0.2, uniqueChatters = 20)).score
        assertTrue(b > a)
        assertTrue(c > b)
    }

    @Test
    fun missingSignalsAreIgnored() {
        assertEquals(0, InterestScorer.score(MomentSignals()).score)
        assertEquals(100, InterestScorer.score(MomentSignals(heatmap = 1.0)).score)
        assertEquals(50, InterestScorer.score(MomentSignals(audioJumpDb = 5.0)).score)
    }

    @Test
    fun heatmapStrengthTopPeakIsOne() {
        assertEquals(1.0, InterestScorer.heatmapStrength(1.0, 1.0, 0.2), 1e-9)
        val mid = InterestScorer.heatmapStrength(0.6, 1.0, 0.2)
        assertEquals(0.5 * 0.6 + 0.5 * 0.5, mid, 1e-9)
    }
}
