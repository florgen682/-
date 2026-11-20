package app.clipradar.core

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaLogicTest {
    // ---------- Субтитры ----------

    @Test
    fun subtitlesAre2To3WordsAndInClipTime() {
        var t = 10.0
        val words = "so then he jumps over the wall and falls. lol".split(" ").map { w -> Word(w, t, t + 0.3).also { t += 0.35 } }
        val cues = Subtitles.cues(words, clipStart = 10.0, clipEnd = 20.0)
        assertTrue(cues.isNotEmpty())
        assertTrue(cues.all { it.text.split(" ").size <= 3 }, cues.toString())
        assertTrue(cues.all { it.text == it.text.uppercase() })
        assertEquals(0.0, cues.first().start, 1e-9)
        assertTrue(cues.zipWithNext().all { (a, b) -> a.end <= b.start + 1e-9 }, "cues must not overlap")
        assertTrue(cues.any { it.text.endsWith("FALLS.") }, "sentence end closes a cue: $cues")
    }

    @Test
    fun subtitlesSkipNoiseTags() {
        val words = listOf(Word("[MUSIC]", 0.0, 1.0), Word("hello", 1.0, 1.3), Word("(laughs)", 1.3, 2.0))
        assertEquals(listOf("HELLO"), Subtitles.cues(words, 0.0, 5.0).map { it.text })
    }

    // ---------- Подписи ----------

    @Test
    fun captionsFollowRules() {
        var t = 0.0
        val words = "haha no way he really built the castle castle in minecraft haha lmao".split(" ")
            .map { Word(it, t, t + 0.3).also { t += 0.4 } }
        val texts = CaptionWriter.generate("xQc", "https://www.twitch.tv/xqc", "Minecraft", words, "twitch")
        assertEquals(3, texts.titles.size)
        for (title in texts.titles) {
            assertTrue(title.length <= 60, title)
            assertTrue(title.contains("😂") || title.contains("😱") || title.contains("🔥"), title)
        }
        assertTrue(texts.hook.split(" ").size in 2..4, texts.hook)
        assertTrue(texts.hashtags.size in 3..5, texts.hashtags.toString())
        assertTrue(texts.description.contains("https://www.twitch.tv/xqc"))
        assertTrue(texts.description.contains("xQc"))
        assertTrue("castle" in CaptionWriter.keywords(words))
        assertEquals(MomentMood.LAUGH, CaptionWriter.mood(words))
    }

    @Test
    fun keywordsSkipContractionsAndPreferNames() {
        var t = 0.0
        val words = "so you're listening and you're like okay then Granny said where is Granny going you're crazy"
            .split(" ").map { Word(it, t, t + 0.3).also { t += 0.4 } }
        val kw = CaptionWriter.keywords(words)
        assertEquals("granny", kw.first())
        assertTrue(kw.none { it.contains("'") }, kw.toString())
        assertTrue("listening" !in kw)
    }

    @Test
    fun longTitlesAreShortened() {
        val title = CaptionWriter.fit("Supercalifragilistic Streamer Name Lost It Over Something Very Long Indeed 😂", 60)
        assertTrue(title.length <= 60, title)
        assertTrue(title.endsWith("😂"))
    }

    // ---------- Громкость ----------

    private fun sine(amp: Double, sr: Int, seconds: Double, freq: Double = 1000.0) =
        FloatArray((sr * seconds).toInt()) { i -> (amp * sin(2 * PI * freq * i / sr)).toFloat() }

    @Test
    fun lufsOfReferenceSine() {
        // Синус 1 кГц амплитудой 0.1 в моно ≈ −23 LUFS.
        val m = LufsMeter(48_000, 1)
        sine(0.1, 48_000, 5.0).forEach(m::feed)
        assertEquals(-23.0, m.integrated()!!, 0.3)
        val expectedAt44k = LufsMeter(44_100, 1).apply { sine(0.1, 44_100, 5.0).forEach(::feed) }.integrated()!!
        assertEquals(-23.0, expectedAt44k, 0.3)
    }

    @Test
    fun gainBringsTo14LufsButLimitsPeaks() {
        val g = LufsMeter.gainFor(measuredLufs = -23.0, peak = 0.1)
        assertEquals(9.0, 20 * log10(g), 0.01) // +9 дБ: −23 → −14
        val limited = LufsMeter.gainFor(measuredLufs = -23.0, peak = 0.8)
        assertTrue(20 * log10(0.8 * limited) <= -1.0 + 1e-9) // пик не выше −1 dBFS
        assertEquals(1.0, LufsMeter.gainFor(null, 0.5))
    }

    @Test
    fun lufsOfSubRange() {
        val m = LufsMeter(48_000, 1)
        sine(0.01, 48_000, 5.0).forEach(m::feed) // тихо (≈ −43 LUFS)
        sine(0.1, 48_000, 5.0).forEach(m::feed)  // громче (≈ −23 LUFS)
        assertEquals(-23.0, m.integrated(5.0, 10.0)!!, 0.3)
        assertEquals(-43.0, m.integrated(0.0, 5.0)!!, 0.3)
        assertEquals(0.01, m.peak(0.5, 4.5), 0.001)
    }

    @Test
    fun silenceHasNoLoudness() {
        val m = LufsMeter(48_000, 2)
        repeat(48_000 * 2) { m.feed(0f) }
        assertNull(m.integrated())
    }

    @Test
    fun resamplerTo16kMono() {
        val r = MonoResampler(48_000, 2)
        val s = sine(0.5, 48_000, 1.0, 440.0)
        for (v in s) { r.feed(v); r.feed(v) } // стерео: одинаково в обоих каналах
        val out = r.result()
        assertTrue(abs(out.size - 16_000) <= 2, "size=${out.size}")
        assertEquals(0.5, out.maxOf { it }.toDouble(), 0.02)
    }

    // ---------- Кадрирование ----------

    @Test
    fun fillModeHasNoBlackBars() {
        val inAspect = 16.0 / 9
        val outAspect = 9.0 / 16
        val (w, h) = CropPlanner.fillCropSize(inAspect, outAspect)
        for (cx in listOf(w / 2, 0.3, 0.5, 0.8, 1 - w / 2)) {
            val src = NRect(cx - w / 2, 0.0, w, h)
            assertTrue(CropPlanner.coversWithoutBars(listOf(NRect(0.0, 0.0, 1.0, 1.0) to src), inAspect, outAspect))
        }
        // Старый режим «чёрные поля» не прошёл бы проверку: видео занимает только середину.
        val letterbox = listOf(NRect(0.0, 0.342, 1.0, 0.316) to NRect(0.0, 0.0, 1.0, 1.0))
        assertFalse(CropPlanner.coversWithoutBars(letterbox, inAspect, outAspect))
    }

    @Test
    fun streamLayoutHasNoBlackBars() {
        val inAspect = 16.0 / 9
        val face = NRect(0.06, 0.70, 0.06, 0.1)
        val cam = CropPlanner.estimateCamRect(face.cx, face.cy, face.w, face.h)
        val l = CropPlanner.streamLayout(inAspect, cam, face)
        val regions = listOf(
            NRect(0.0, 0.0, 1.0, l.camFraction) to l.cam,
            NRect(0.0, l.camFraction, 1.0, 1 - l.camFraction) to l.game,
        )
        assertTrue(CropPlanner.coversWithoutBars(regions, inAspect, 9.0 / 16), "layout=$l")
        assertTrue(l.cam.cx in (face.cx - 0.1)..(face.cx + 0.1))
    }

    @Test
    fun smartTrackFollowsFaceSmoothly() {
        val (w, _) = CropPlanner.fillCropSize(16.0 / 9, 9.0 / 16)
        // Лицо резко прыгает слева направо на 5-й секунде + случайный выброс.
        val samples = (0..40).map { i ->
            val t = i * 0.25
            val x = when {
                i == 7 -> 0.95 // выброс
                t < 5 -> 0.3
                else -> 0.7
            }
            FrameSample(t, listOf(FaceBox(t, NRect(x - 0.05, 0.3, 0.1, 0.15))))
        }
        val track = CropPlanner.smartTrack(samples, w)
        assertTrue(track.all { it.cx in (w / 2 - 1e-9)..(1 - w / 2 + 1e-9) })
        val speeds = track.zipWithNext().map { (a, b) -> abs(b.cx - a.cx) / (b.t - a.t) }
        assertTrue(speeds.max() <= 0.26, "max speed ${speeds.max()}")
        assertEquals(0.3, track[5].cx, 0.05) // выброс на 7-м кадре проигнорирован
        assertTrue(track.last().cx > 0.6)
        assertEquals(0.5, CropPlanner.centerAt(listOf(CropKey(0.0, 0.4), CropKey(2.0, 0.6)), 1.0), 1e-9)
    }

    @Test
    fun webcamDetectedOnlyForSmallStaticCornerFace() {
        val cam = (0 until 20).map { i -> FrameSample(i.toDouble(), listOf(FaceBox(i.toDouble(), NRect(0.82, 0.05, 0.07, 0.12)))) }
        assertNotNull(CropPlanner.detectWebcam(cam))
        val irl = (0 until 20).map { i -> FrameSample(i.toDouble(), listOf(FaceBox(i.toDouble(), NRect(0.4, 0.3, 0.2, 0.35)))) }
        assertNull(CropPlanner.detectWebcam(irl))
        val rare = cam.mapIndexed { i, s -> if (i % 3 == 0) s else s.copy(faces = emptyList()) }
        assertNull(CropPlanner.detectWebcam(rare))
    }

    @Test
    fun webcamBorderFoundByEdges() {
        // Кадр 160×90: тёмная игра, светлая вебка в прямоугольнике x 120..155, y 4..34.
        val w = 160
        val h = 90
        val frames = (0 until 3).map { k ->
            IntArray(w * h) { i ->
                val x = i % w
                val y = i / w
                if (x in 120 until 155 && y in 4 until 34) 200 else ((x * 7 + y * 3 + k * 31) % 40)
            }
        }
        val face = NRect(130.0 / w, 10.0 / h, 12.0 / w, 14.0 / h)
        val guess = CropPlanner.estimateCamRect(face.cx, face.cy, face.w, face.h)
        val r = CropPlanner.refineCamRect(guess, face, frames, w, h)
        assertEquals(120.0 / w, r.x, 0.02)
        assertEquals(155.0 / w, r.right, 0.02)
        assertEquals(4.0 / h, r.y, 0.03)
        assertEquals(34.0 / h, r.bottom, 0.03)
    }

    // ---------- Качество и обратная связь ----------

    @Test
    fun rejectsClipsWithMoreThan40PercentSilenceOrStatic() {
        val loud = List(30) { if (it < 15) -60.0 else -20.0 } // половина — тишина
        assertTrue(QualityCheck.isBad(QualityCheck.badFraction(loud, emptyList())))
        val ok = List(30) { if (it < 5) -60.0 else -20.0 }
        val diffs = List(30) { if (it in 10..14) 0.2 else 12.0 } // 5 с «паузы»
        assertFalse(QualityCheck.isBad(QualityCheck.badFraction(ok, diffs))) // 10/30 = 33%
        assertTrue(QualityCheck.isBad(QualityCheck.badFraction(ok, List(30) { if (it in 5..12) 0.1 else 12.0 })))
    }

    @Test
    fun feedbackAdjustsThreshold() {
        var a = 0
        repeat(3) { a = FeedbackTuner.adjust(a, good = false) }
        assertEquals(12, a)
        assertEquals(72, FeedbackTuner.threshold(60, a))
        assertTrue(FeedbackTuner.chatMultiplierScale(a) > 1.0)
        repeat(20) { a = FeedbackTuner.adjust(a, good = true) }
        assertEquals(-20, a)
    }

    @Test
    fun chatPerSecondTimeline() {
        val t = ChatActivityTracker()
        t.start(0)
        t.add(500, "a", "hi")
        t.add(1500, "b", "lol")
        t.add(1600, "c", "hey")
        assertEquals(listOf(1.0, 3.0, 0.0), t.perSecond(0, 3000))
    }
}
