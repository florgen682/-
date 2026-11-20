package app.clipradar.core

import org.junit.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundariesTest {
    /** Фраза: слова по 0.3 с с паузами 0.1 с, начиная с [start]. */
    private fun phrase(start: Double, vararg texts: String): List<Word> {
        var t = start
        return texts.map { w -> Word(w, t, t + 0.3).also { t += 0.4 } }
    }

    private fun insideAnyWord(t: Double, words: List<Word>) = words.any { t > it.start + 0.02 && t < it.end - 0.02 }

    /** Громкость: тихо (-30 дБ), а в [loudFrom, loudTo] — громко (-10 дБ). Шаг 0.5 с. */
    private fun loudness(d: Double, loudFrom: Double, loudTo: Double) =
        (0 until (d / 0.5).toInt()).map { i -> val t = i * 0.5; if (t in loudFrom..loudTo) -10.0 else -30.0 }

    @Test
    fun startsAtSentenceAfterPause() {
        // Разговор, пауза 1.5 с, новая фраза с 40.0 с, а на 44 с — взрыв смеха.
        val words = phrase(30.0, "so", "we", "were", "walking", "home") +
            phrase(40.0, "and", "then", "this", "guy", "just", "jumped", "out") +
            phrase(47.0, "haha", "that", "was", "crazy.")
        val ctx = MomentContext(
            durationSec = 120.0, peakSec = 45.0, words = words,
            loudnessDb = loudness(120.0, 44.0, 47.0),
        )
        val b = BoundaryPicker.pick(ctx)
        assertTrue(b.start in 39.7..40.0, "start=${b.start} (${b.startReason})")
        assertTrue(b.startReason.contains("паузы"), b.startReason)
        assertTrue(!insideAnyWord(b.start, words) && !insideAnyWord(b.end, words))
    }

    @Test
    fun neverCutsInsideAWord_randomized() {
        val rnd = Random(42)
        repeat(300) {
            val words = ArrayList<Word>()
            var t = rnd.nextDouble(0.0, 3.0)
            while (t < 115) {
                val len = rnd.nextDouble(0.15, 0.9)
                words += Word(if (rnd.nextInt(10) == 0) "end." else "w", t, t + len)
                t += len + if (rnd.nextInt(6) == 0) rnd.nextDouble(0.5, 3.0) else rnd.nextDouble(0.02, 0.2)
            }
            val peak = rnd.nextDouble(10.0, 110.0)
            val ctx = MomentContext(
                durationSec = 120.0, peakSec = peak, words = words,
                loudnessDb = loudness(120.0, peak - 1, peak + rnd.nextDouble(1.0, 15.0)),
                sceneCuts = List(5) { rnd.nextDouble(0.0, 120.0) },
            )
            val cfg = BoundsConfig(maxLenSec = listOf(30.0, 60.0, 180.0)[rnd.nextInt(3)])
            val b = BoundaryPicker.pick(ctx, cfg)
            assertTrue(!insideAnyWord(b.start, words), "start ${b.start} inside a word")
            assertTrue(!insideAnyWord(b.end, words), "end ${b.end} inside a word")
            assertTrue(b.length <= cfg.maxLenSec + 1e-6, "length ${b.length} > ${cfg.maxLenSec}")
            assertTrue(b.length >= cfg.minLenSec - 1.0, "length ${b.length} too short")
            assertTrue(b.start >= 0 && b.end <= 120.0)
        }
    }

    @Test
    fun longNothingBeforePeakGivesAtMost3SecondsLead() {
        // Фраза давно (10 с), потом 25 с тишины, в 40 с — крик.
        val words = phrase(10.0, "okay", "let's", "wait.") + phrase(40.5, "oh", "my", "god", "no", "way.")
        val ctx = MomentContext(
            durationSec = 90.0, peakSec = 41.0, words = words,
            loudnessDb = loudness(90.0, 40.0, 43.0),
        )
        val b = BoundaryPicker.pick(ctx)
        val onsetLead = 40.0 - b.start
        assertTrue(onsetLead <= 3.0 + 1e-6, "lead=$onsetLead start=${b.start} (${b.startReason})")
        assertTrue(b.length >= 12.0 - 1e-6) // всё равно не короче минимума (добрано после пика)
    }

    @Test
    fun endsWhenChatAndVolumeCalmAndSentenceEnds_plusTail() {
        // Чат: норма 1/с, с 20 по 28 с — ×5. Громко 20..26 с. Чат успокаивается, пока стример
        // ещё говорит «that was so good.» — конец должен дождаться конца фразы.
        val chat = List(80) { s -> if (s in 20..28) 5.0 else 1.0 }
        val words = phrase(18.0, "watch", "this") + phrase(30.4, "that", "was", "so", "good.") +
            phrase(36.0, "anyway", "next")
        val ctx = MomentContext(
            durationSec = 80.0, peakSec = 24.0, words = words,
            loudnessDb = loudness(80.0, 20.0, 26.0),
            chatPerSec = chat, chatBaseline = 1.0,
        )
        val b = BoundaryPicker.pick(ctx)
        val sentenceEnd = words.first { it.text == "good." }.end
        assertTrue(b.end - sentenceEnd in 1.0..1.5, "end=${b.end}, sentence ends $sentenceEnd (${b.endReason})")
        assertTrue(!insideAnyWord(b.end, words))
    }

    @Test
    fun tailNeverEntersNextWord() {
        val words = phrase(10.0, "big", "moment", "here.") + Word("next", 11.6 + 0.6, 12.6 + 0.6)
        val ctx = MomentContext(durationSec = 60.0, peakSec = 10.5, words = words, loudnessDb = loudness(60.0, 10.0, 11.0))
        val b = BoundaryPicker.pick(ctx, BoundsConfig(minLenSec = 1.0))
        assertTrue(!insideAnyWord(b.end, words), "end=${b.end}")
    }

    @Test
    fun longMomentIsLimitedAndKeepsStrongestPart() {
        // Всё окно громкое 200 с — реакция «не кончается». Пик на 150 с.
        val words = (0 until 60).flatMap { k -> phrase(k * 3.3, "word", "word", "end.") }
        val loud = (0 until 400).map { i -> if (i * 0.5 in 140.0..160.0) -5.0 else -15.0 }
        val ctx = MomentContext(durationSec = 200.0, peakSec = 150.0, words = words, loudnessDb = loud)
        val b = BoundaryPicker.pick(ctx, BoundsConfig(maxLenSec = 60.0))
        assertTrue(b.length <= 60.0 + 1e-6, "length=${b.length}")
        assertTrue(b.peak in b.start..b.end, "peak ${b.peak} must be inside ${b.start}..${b.end}")
    }

    @Test
    fun shortMomentIsExtendedToMinimum() {
        val words = phrase(50.0, "lol.")
        val ctx = MomentContext(durationSec = 100.0, peakSec = 50.2, words = words, loudnessDb = loudness(100.0, 50.0, 51.0))
        val b = BoundaryPicker.pick(ctx)
        assertTrue(b.length >= 12.0 - 1e-6, "length=${b.length}")
        assertTrue(b.peak in b.start..b.end)
    }

    @Test
    fun hookOnlyWhenPeakIsLaterThan3Seconds() {
        val words = phrase(30.0, "so", "here", "is", "the", "story", "about", "my", "cat.") +
            phrase(34.0, "and", "it", "fell", "off", "the", "table")
        val ctx = MomentContext(durationSec = 90.0, peakSec = 37.0, words = words, loudnessDb = loudness(90.0, 36.5, 39.0))
        val b = BoundaryPicker.pick(ctx)
        val hook = assertNotNull(b.hook)
        assertTrue(hook.durationSec in 1.0..2.6, "hook=${hook}")
        assertTrue(b.peak in hook.startSec..hook.endSec + 0.5)

        val early = BoundaryPicker.pick(ctx.copy(peakSec = 30.5, loudnessDb = loudness(90.0, 30.0, 32.0), words = phrase(30.0, "wow")))
        if (early.peak - early.start <= 3.0) assertNull(early.hook)
    }

    @Test
    fun mergesMomentsCloserThan20Seconds() {
        val groups = MomentMerger.merge(
            listOf(TimeRange(100.0, 130.0), TimeRange(145.0, 170.0), TimeRange(300.0, 330.0), TimeRange(10.0, 40.0)),
        )
        assertEquals(3, groups.size)
        assertEquals(TimeRange(100.0, 170.0), groups[1].first)
        assertEquals(listOf(0, 1), groups[1].second)
        // Ровно 20 с — уже не объединяем.
        assertEquals(2, MomentMerger.merge(listOf(TimeRange(0.0, 10.0), TimeRange(30.0, 40.0))).size)
    }

    @Test
    fun peakPrefersTheRealReactionOverEarlierLoudSpeech() {
        // Речь 30–45 с громче тишины, но настоящая реакция (крик) 50–56 с ещё громче.
        val loud = (0 until 240).map { i ->
            val t = i * 0.5
            when {
                t in 50.0..56.0 -> -4.0
                t in 30.0..45.0 -> -20.0
                else -> -40.0
            }
        }
        val words = phrase(30.0, "so", "i", "was", "walking", "home.") + phrase(48.0, "wait", "what", "oh", "my", "god", "no", "way.") +
            phrase(57.5, "that", "was", "insane", "haha.")
        val b = BoundaryPicker.pick(MomentContext(durationSec = 120.0, peakSec = 52.0, words = words, loudnessDb = loud))
        assertTrue(b.peak in 50.0..56.5, "peak=${b.peak}")
        assertTrue(b.end > 56.0, "clip must include the reaction: end=${b.end} (${b.endReason})")
        assertTrue(b.start in 47.0..50.0, "start=${b.start} (${b.startReason})")
    }

    @Test
    fun mergeDoesNotChainIntoHugeGroups() {
        // Слабые пики каждые 30 с: соседние ближе 20 с (окна ±10), но группа не длиннее 60 с.
        val ranges = (0 until 8).map { k -> TimeRange(k * 30.0 - 10, k * 30.0 + 10) }
        val groups = MomentMerger.merge(ranges)
        assertTrue(groups.all { it.first.durationSec <= 80.0 + 1e-9 }, groups.toString()) // максимум 2 момента + 60 с
        assertTrue(groups.none { it.second.size > 2 && it.first.durationSec > 60.0 + 1e-9 }, groups.toString())
        assertTrue(groups.size >= 4)
    }

    @Test
    fun mergedRangeIsIncludedInBounds() {
        val words = phrase(40.0, "first", "thing.") + phrase(70.0, "second", "thing.")
        val ctx = MomentContext(
            durationSec = 150.0, peakSec = 41.0, words = words, loudnessDb = loudness(150.0, 40.0, 42.0),
            mustInclude = TimeRange(40.0, 72.0),
        )
        val b = BoundaryPicker.pick(ctx)
        assertTrue(b.start <= 40.0 && b.end >= 72.0, "${b.start}..${b.end}")
    }

    @Test
    fun duplicateDetectionByOverlap() {
        assertTrue(MomentMerger.isDuplicate(TimeRange(0.0, 30.0), TimeRange(10.0, 40.0)))  // 20/30 = 67%
        assertTrue(!MomentMerger.isDuplicate(TimeRange(0.0, 30.0), TimeRange(20.0, 50.0))) // 10/30 = 33%
        assertEquals(85, MomentMerger.boostedScore(80, 70))
        assertEquals(100, MomentMerger.boostedScore(99, 98))
    }
}
