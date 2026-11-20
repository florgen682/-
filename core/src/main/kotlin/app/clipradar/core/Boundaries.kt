package app.clipradar.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Слово из расшифровки речи с таймкодами (секунды от начала окна). */
data class Word(val text: String, val start: Double, val end: Double)

/**
 * Всё, что известно о моменте внутри «окна» исходника (секунды от начала окна).
 * Любой сигнал может отсутствовать — тогда он просто не учитывается.
 */
data class MomentContext(
    /** Длина окна (сколько секунд исходника у нас есть). */
    val durationSec: Double,
    /** Где сработал детектор (всплеск чата / пик графика / громкий звук). */
    val peakSec: Double,
    val words: List<Word> = emptyList(),
    /** Громкость в дБ с шагом [loudnessStepSec]. */
    val loudnessDb: List<Double> = emptyList(),
    val loudnessStepSec: Double = 0.5,
    /** Резкие смены картинки (секунды). */
    val sceneCuts: List<Double> = emptyList(),
    /** Взвешенные сообщения чата за каждую секунду окна. */
    val chatPerSec: List<Double> = emptyList(),
    /** «Обычный» чат — сообщений в секунду. */
    val chatBaseline: Double? = null,
    /** Отрезок, который обязательно должен войти (объединённые моменты). */
    val mustInclude: TimeRange? = null,
)

data class BoundsConfig(
    val minLenSec: Double = 12.0,
    val maxLenSec: Double = 60.0,
    /** Пауза в речи, которая считается границей мысли. */
    val pauseSec: Double = 0.7,
    /** Если до пика дольше этого «ничего» — сокращаем подводку. */
    val longNothingSec: Double = 10.0,
    val maxLeadSec: Double = 3.0,
    val tailMinSec: Double = 1.0,
    val tailMaxSec: Double = 1.5,
    /** Зрители пишут в чат с опозданием — на столько раньше было само событие. */
    val chatDelaySec: Double = 3.0,
)

/** Итоговые границы клипа и объяснение, почему они такие. */
data class ClipBounds(
    val start: Double,
    val end: Double,
    /** Самая сильная точка реакции. */
    val peak: Double,
    val startReason: String,
    val endReason: String,
    /** Кусок пика для «хука в начале» (если пик позже 3-й секунды клипа). */
    val hook: TimeRange?,
) {
    val length: Double get() = end - start
}

/**
 * Выбирает начало и конец клипа по контексту: речь, паузы, смены сцен, чат и громкость.
 *
 * Правила:
 *  - начало — начало мысли/ситуации перед событием (начало фразы после паузы > 0.7 с,
 *    смена сцены, момент, когда чат начал расти); если перед пиком долгое «ничего» — максимум 3 с подводки;
 *  - конец — когда чат и громкость вернулись к обычным, реакция затихла и фраза закончилась, + 1–1.5 с хвоста;
 *  - никогда не режем посреди слова;
 *  - длина от minLen до maxLen; если момент длиннее — выбираем самый сильный отрезок.
 */
object BoundaryPicker {
    private const val STEP = 0.5
    private val hypeSpeech = Regex(
        "laugh|haha|ahah|lol|lmao|scream|no way|oh my god|what the|holy|хаха|ахах|смеётся|смех|крик|жесть|ору|wow|bro",
        RegexOption.IGNORE_CASE,
    )
    private val sentenceEnd = Regex("[.!?…]+[\"')\\]]*$")

    fun pick(ctx: MomentContext, cfg: BoundsConfig = BoundsConfig()): ClipBounds {
        val d = max(ctx.durationSec, 1.0)
        val words = ctx.words.filter { it.end > it.start && it.end > 0 && it.start < d }.sortedBy { it.start }
        val sig = Signals(ctx.copy(durationSec = d), words)
        val snap = Snapper(words, d)

        // 1) Самая сильная точка рядом с тем местом, где сработал детектор.
        val p0 = ctx.peakSec.coerceIn(0.0, d)
        val peak = sig.argMaxActivity(p0 - 8, p0 + 5, p0) ?: p0

        // 2) Когда началась реакция (onset).
        val onset = sig.onset(peak, cfg.chatDelaySec)

        // 3) Начало клипа.
        var startReason: String
        val starts = startCandidates(words, sig, snap, cfg, onset)
        var s = starts.filter { it.first <= onset + 1e-6 }.maxByOrNull { it.first }
        var start: Double
        if (s == null) {
            start = snap.outOfWordBack(max(0.0, onset - cfg.maxLeadSec))
            startReason = "нет речи и смен сцен — ${fmt(onset - start)} с подводки"
        } else {
            start = s.first
            startReason = s.second
        }
        // Долгое «ничего» перед пиком → максимум 3 с подводки.
        var leadLimited = false
        if (onset - start > cfg.longNothingSec && sig.longestBoringRun(start, onset, words) > cfg.longNothingSec) {
            start = leadStart(onset, cfg.maxLeadSec, words, snap)
            leadLimited = true
            startReason = "до пика больше ${fmt(cfg.longNothingSec)} с тишины/скучной речи — подводка ${fmt(onset - start)} с"
        }

        // 4) Конец клипа.
        val reactEnd = sig.reactionEnd(peak)
        val (sentEnd, sentReason) = sentenceEndAfter(reactEnd, words, d)
        var end = tailAfter(sentEnd, words, d, cfg)
        var endReason = sentReason + ", хвост ${fmt(end - sentEnd)} с"
        if (end < peak + 0.5) end = snap.outOfWordForward(min(d, peak + 0.5))

        // Объединённые моменты должны войти целиком.
        ctx.mustInclude?.let { r ->
            if (r.startSec < start) {
                start = snap.outOfWordBack(max(0.0, r.startSec))
                startReason += "; расширено, чтобы вошёл соседний момент"
            }
            if (r.endSec > end) {
                end = snap.outOfWordForward(min(d, r.endSec))
                endReason += "; расширено, чтобы вошёл соседний момент"
            }
        }

        // 5) Ограничения длины.
        if (end - start < cfg.minLenSec) {
            val (ns, ne) = extendToMin(start, end, words, snap, cfg, d, preferForward = leadLimited)
            if (ns < start) startReason += "; раньше — чтобы клип был не короче ${fmt(cfg.minLenSec)} с"
            if (ne > end) endReason += "; позже — чтобы клип был не короче ${fmt(cfg.minLenSec)} с"
            start = ns
            end = ne
        }
        if (end - start > cfg.maxLenSec) {
            val best = bestWindow(start, end, peak, words, sig, snap, cfg)
            start = best.first
            end = best.second
            startReason += "; момент длиннее ${fmt(cfg.maxLenSec)} с — выбран самый сильный отрезок"
            endReason = "ограничение ${fmt(cfg.maxLenSec)} с, конец на границе слова"
        }
        start = snap.outOfWordBack(start.coerceIn(0.0, d))
        end = snap.outOfWordForward(end.coerceIn(0.0, d))
        if (end - start > cfg.maxLenSec) end = snap.outOfWordBack(start + cfg.maxLenSec)

        // 6) Хук: если пик позже 3-й секунды клипа — короткий кусок пика в начало.
        val hook = if (peak - start > 3.0) {
            val hs = snap.outOfWordBack(max(start, peak - 0.8))
            var he = snap.outOfWordForward(hs + 1.5)
            if (he - hs > 2.5) he = snap.outOfWordBack(hs + 2.0).let { if (it - hs < 1.0) hs + 2.0 else it }
            TimeRange(hs, min(he, end))
        } else {
            null
        }
        return ClipBounds(start, end, peak, startReason, endReason, hook)
    }

    // ---------- Начало ----------

    private fun startCandidates(
        words: List<Word>,
        sig: Signals,
        snap: Snapper,
        cfg: BoundsConfig,
        onset: Double,
    ): List<Pair<Double, String>> {
        val out = ArrayList<Pair<Double, String>>()
        for (i in words.indices) {
            val w = words[i]
            val prevEnd = if (i == 0) null else words[i - 1].end
            val gap = if (prevEnd == null) Double.MAX_VALUE else w.start - prevEnd
            val afterPunct = i > 0 && sentenceEnd.containsMatchIn(words[i - 1].text.trim())
            if (gap > cfg.pauseSec || afterPunct) {
                val pad = if (prevEnd == null) 0.2 else min(0.2, max(0.0, gap / 2))
                val t = max(0.0, w.start - pad)
                val why = when {
                    prevEnd == null -> "начало первой фразы в окне"
                    gap > cfg.pauseSec -> "начало фразы после паузы ${fmt(gap)} с"
                    else -> "начало нового предложения"
                }
                out += t to why
            }
        }
        for (c in sig.sceneCuts) {
            if (!snap.insideWord(c)) out += c to "смена сцены"
        }
        sig.chatRise()?.let { r ->
            val t = snap.outOfWordBack(max(0.0, r - cfg.chatDelaySec))
            out += t to "чат начал расти (с поправкой ${fmt(cfg.chatDelaySec)} с на задержку)"
        }
        return out.filter { it.first < onset + 1e-6 || it.first <= 0.0 }
    }

    /** Самая ранняя граница слова не дальше [lead] секунд до onset. */
    private fun leadStart(onset: Double, lead: Double, words: List<Word>, snap: Snapper): Double {
        val from = max(0.0, onset - lead)
        if (!snap.insideWord(from)) return from
        // Внутри слова — начинаем со следующего слова (не раньше, чтобы не превысить 3 с).
        val next = words.firstOrNull { it.start >= from }
        return if (next != null && next.start <= onset) max(from, next.start - 0.05) else snap.outOfWordForward(from)
    }

    // ---------- Конец ----------

    private fun sentenceEndAfter(t: Double, words: List<Word>, d: Double): Pair<Double, String> {
        val inWord = words.firstOrNull { it.start < t && it.end > t }
        val prev = words.lastOrNull { it.end <= t }
        val next = words.firstOrNull { it.start >= t }
        // «В паузе» — только если следующая фраза не начинается сразу (иначе это продолжение реакции).
        val inPause = inWord == null && (prev == null || t - prev.end >= 0.4) &&
            (next == null || next.start - t >= 1.0)
        if (inPause) return t to "реакция затихла, речь в паузе"
        // Ищем конец текущего предложения (знак препинания или пауза > 0.5 с), но не дальше 8 с.
        val fromIdx = words.indexOfFirst { it.end >= t - 0.01 }.let { if (it < 0) words.size else it }
        for (j in fromIdx until words.size) {
            val w = words[j]
            if (w.end > t + 8.0) break
            val nextW = words.getOrNull(j + 1)
            val ends = nextW == null || nextW.start - w.end > 0.5 || sentenceEnd.containsMatchIn(w.text.trim())
            if (ends) return w.end to "реакция затихла и фраза закончилась"
        }
        val capped = words.firstOrNull { it.start < t + 8 && it.end > t + 8 }?.end ?: min(d, t + 8.0)
        return capped to "реакция затихла, фраза слишком длинная — конец на границе слова"
    }

    private fun tailAfter(sentEnd: Double, words: List<Word>, d: Double, cfg: BoundsConfig): Double {
        val next = words.firstOrNull { it.start > sentEnd + 0.01 }
        val wanted = sentEnd + (cfg.tailMinSec + cfg.tailMaxSec) / 2
        val e = when {
            next == null || next.start >= sentEnd + cfg.tailMaxSec -> wanted
            next.start >= sentEnd + cfg.tailMinSec -> next.start - 0.05
            else -> max(sentEnd + 0.05, next.start - 0.03) // короткий хвост — лишь бы не задеть следующее слово
        }
        return min(e, d)
    }

    // ---------- Длина ----------

    private fun extendToMin(
        s0: Double,
        e0: Double,
        words: List<Word>,
        snap: Snapper,
        cfg: BoundsConfig,
        d: Double,
        preferForward: Boolean,
    ): Pair<Double, Double> {
        var s = s0
        var e = e0
        // Если рядом (не дальше, чем не хватает + 2 с) есть начало предыдущей фразы — берём её целиком.
        if (!preferForward) {
            val need = cfg.minLenSec - (e - s)
            val prev = words.indices.filter { i ->
                i == 0 || words[i].start - words[i - 1].end > 0.4 || sentenceEnd.containsMatchIn(words[i - 1].text.trim())
            }.map { max(0.0, words[it].start - 0.15) }
                .filter { it < s - 0.05 && s - it <= need + 2.0 }
                .minOrNull()
            if (prev != null) s = prev
        }
        if (e - s < cfg.minLenSec) {
            // Добираем после пика (до конца фразы), а если окно кончилось — до пика.
            val need = cfg.minLenSec - (e - s)
            e = snap.outOfWordForward(min(d, e + need))
            if (e - s < cfg.minLenSec) {
                s = snap.outOfWordBack(max(0.0, e - cfg.minLenSec))
            }
        }
        return s to e
    }

    private fun bestWindow(
        s0: Double,
        e0: Double,
        peak: Double,
        words: List<Word>,
        sig: Signals,
        snap: Snapper,
        cfg: BoundsConfig,
    ): Pair<Double, Double> {
        val starts = (listOf(s0) + words.map { max(0.0, it.start - 0.1) } + sig.sceneCuts)
            .filter { it >= s0 && it <= e0 - cfg.minLenSec && !snap.insideWord(it) }
            .distinct()
        var best: Triple<Double, Double, Double>? = null
        for (s in starts) {
            val e = snap.outOfWordBack(min(e0, s + cfg.maxLenSec))
            if (e - s < cfg.minLenSec) continue
            var score = sig.activitySum(s, e)
            if (peak in s..e) score += 1e6 // пик обязательно должен войти, если это возможно
            if (best == null || score > best.third + 1e-9) best = Triple(s, e, score)
        }
        return best?.let { it.first to it.second } ?: (s0 to snap.outOfWordBack(s0 + cfg.maxLenSec))
    }

    private fun fmt(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }

    // ---------- Вспомогательное ----------

    /** Работа с границами слов: никогда не режем посреди слова. */
    class Snapper(private val words: List<Word>, private val d: Double) {
        fun insideWord(t: Double): Boolean = wordAt(t) != null

        fun wordAt(t: Double): Word? = words.firstOrNull { t > it.start + 0.02 && t < it.end - 0.02 }

        /** Если t внутри слова — сдвигаемся назад, в паузу перед словом. */
        fun outOfWordBack(t: Double): Double {
            val w = wordAt(t) ?: return t
            val prevEnd = words.lastOrNull { it.end <= w.start + 1e-9 && it !== w }?.end ?: 0.0
            return max(prevEnd, max(0.0, w.start - 0.05)).coerceAtMost(w.start)
        }

        /** Если t внутри слова — сдвигаемся вперёд, в паузу после слова. */
        fun outOfWordForward(t: Double): Double {
            val w = wordAt(t) ?: return t
            val nextStart = words.firstOrNull { it.start >= w.end - 1e-9 && it !== w }?.start ?: d
            return min(nextStart, min(d, w.end + 0.05)).coerceAtLeast(w.end)
        }
    }

    private class Signals(ctx: MomentContext, private val words: List<Word>) {
        val d = ctx.durationSec
        val n = max(1, ceil(d / STEP).toInt())
        val sceneCuts = ctx.sceneCuts.filter { it in 0.0..d }.sorted()
        private val loudEx = DoubleArray(n)
        private val hasLoud = ctx.loudnessDb.isNotEmpty()
        private val chatRatio: DoubleArray?
        private val hype = BooleanArray(n)
        val activity = DoubleArray(n)

        init {
            if (hasLoud) {
                val audible = ctx.loudnessDb.filter { it > -90 }
                val med = if (audible.isEmpty()) 0.0 else PeakFinder.median(audible)
                for (i in 0 until n) {
                    val idx = ((i * STEP) / ctx.loudnessStepSec).toInt().coerceIn(0, ctx.loudnessDb.lastIndex)
                    val v = ctx.loudnessDb[idx]
                    loudEx[i] = if (v > -90) max(0.0, v - med) else 0.0
                }
            }
            chatRatio = if (ctx.chatPerSec.isNotEmpty()) {
                val sm = PeakFinder.smooth(ctx.chatPerSec, 3)
                val base = max(ctx.chatBaseline ?: PeakFinder.median(ctx.chatPerSec), 0.1)
                DoubleArray(n) { i -> sm[((i * STEP).toInt()).coerceIn(0, sm.lastIndex)] / base }
            } else {
                null
            }
            for (w in words) {
                if (hypeSpeech.containsMatchIn(w.text)) {
                    for (i in slot(w.start)..slot(w.end)) hype[i] = true
                }
            }
            for (i in 0 until n) {
                var sum = 0.0
                var wsum = 0.0
                if (hasLoud) {
                    sum += 0.45 * (loudEx[i] / 10.0).coerceIn(0.0, 1.0); wsum += 0.45
                }
                chatRatio?.let { sum += 0.45 * ((it[i] - 1.0) / 3.0).coerceIn(0.0, 1.0); wsum += 0.45 }
                sum += 0.10 * (if (hype[i]) 1.0 else 0.0); wsum += 0.10
                activity[i] = sum / wsum
            }
        }

        fun slot(t: Double) = (t / STEP).toInt().coerceIn(0, n - 1)
        fun time(i: Int) = i * STEP

        /**
         * Самая сильная точка рядом с детектором. При равной «активности» побеждает то, что громче
         * (без ограничения сверху) и ближе к месту срабатывания детектора [near].
         */
        fun argMaxActivity(from: Double, to: Double, near: Double = (from + to) / 2): Double? {
            var best = -1
            var bestKey = Double.NEGATIVE_INFINITY
            for (i in slot(max(0.0, from))..slot(min(d, to))) {
                val chat = chatRatio?.get(i) ?: 0.0
                val key = activity[i] * 1000 + loudEx[i] + chat - kotlin.math.abs(time(i) - near) * 0.01
                if (key > bestKey) {
                    bestKey = key
                    best = i
                }
            }
            if (best < 0 || activity[best] <= 0.0) return null
            return time(best) + STEP / 2
        }

        /** Начало реакции: идём назад от пика, пока громкость/чат выше обычного. */
        fun onset(peak: Double, chatDelay: Double): Double {
            val p = slot(peak)
            val candidates = ArrayList<Double>()
            if (hasLoud && loudEx[p] > 0) {
                val thr = max(3.0, 0.4 * loudEx[p])
                var i = p
                while (i > 0 && loudEx[i - 1] >= thr) i--
                candidates += time(i)
            }
            chatRise()?.let { candidates += max(0.0, it - chatDelay) }
            val o = candidates.minOrNull() ?: max(0.0, peak - 2.0)
            return o.coerceIn(max(0.0, peak - 20.0), peak)
        }

        private var chatRiseCache: Double? = null
        private var chatRiseDone = false

        /** Когда чат начал расти перед пиком. */
        fun chatRise(): Double? {
            if (chatRiseDone) return chatRiseCache
            chatRiseDone = true
            val r = chatRatio ?: return null
            var p = 0
            for (i in r.indices) if (r[i] > r[p]) p = i
            if (r[p] < 1.5) return null
            var i = p
            while (i > 0 && r[i - 1] >= 1.5) i--
            chatRiseCache = time(i)
            return chatRiseCache
        }

        /** Реакция закончилась: 1.5 с подряд чат ≤ ×1.3, громкость ≤ +3 дБ и нет смеха. */
        fun reactionEnd(peak: Double): Double {
            val hold = (1.5 / STEP).toInt()
            var i = slot(peak + 1.0)
            while (i < n) {
                var ok = true
                for (k in i until min(n, i + hold)) {
                    val calmChat = chatRatio == null || chatRatio[k] <= 1.3
                    val calmLoud = !hasLoud || loudEx[k] <= 3.0
                    if (!calmChat || !calmLoud || hype[k]) {
                        ok = false
                        break
                    }
                }
                if (ok) return time(i)
                i++
            }
            return d
        }

        /** Самый длинный кусок «ничего» (нет речи и тихо, или очень низкая активность). */
        fun longestBoringRun(from: Double, to: Double, words: List<Word>): Double {
            var best = 0
            var cur = 0
            for (i in slot(from)..slot(to)) {
                val t0 = time(i)
                val speech = words.any { it.start < t0 + STEP && it.end > t0 }
                val boring = (!speech && (!hasLoud || loudEx[i] < 2.0)) || activity[i] < 0.1
                cur = if (boring) cur + 1 else 0
                if (cur > best) best = cur
            }
            return best * STEP
        }

        fun activitySum(from: Double, to: Double): Double {
            var s = 0.0
            for (i in slot(from)..slot(to)) s += activity[i]
            return s
        }
    }
}

/** Объединение близких моментов и поиск дублей. */
object MomentMerger {
    /**
     * Группы моментов, между которыми меньше [gapSec] секунд. Два момента объединяются всегда, а третий и
     * дальше — только пока группа не длиннее [maxSpanSec] (иначе цепочка слабых моментов склеилась бы
     * в один огромный). Возвращает отрезки и номера моментов.
     */
    fun merge(ranges: List<TimeRange>, gapSec: Double = 20.0, maxSpanSec: Double = 60.0): List<Pair<TimeRange, List<Int>>> {
        val order = ranges.indices.sortedBy { ranges[it].startSec }
        val out = ArrayList<Pair<TimeRange, MutableList<Int>>>()
        for (i in order) {
            val r = ranges[i]
            val last = out.lastOrNull()
            // Два близких момента объединяем всегда; дальше группа растёт, только пока она не длиннее maxSpanSec.
            val fits = last != null && (last.second.size == 1 || max(last.first.endSec, r.endSec) - last.first.startSec <= maxSpanSec)
            if (last != null && r.startSec - last.first.endSec < gapSec && fits) {
                out[out.lastIndex] = TimeRange(last.first.startSec, max(last.first.endSec, r.endSec)) to last.second.apply { add(i) }
            } else {
                out += r to mutableListOf(i)
            }
        }
        return out
    }

    /** Какая часть более короткого отрезка перекрыта другим (0..1). */
    fun overlapFraction(a: TimeRange, b: TimeRange): Double {
        val inter = min(a.endSec, b.endSec) - max(a.startSec, b.startSec)
        if (inter <= 0) return 0.0
        val shorter = min(a.durationSec, b.durationSec)
        return if (shorter <= 0) 0.0 else inter / shorter
    }

    fun isDuplicate(a: TimeRange, b: TimeRange) = overlapFraction(a, b) > 0.5

    /** Новая оценка старого момента, если нашёлся его дубль. */
    fun boostedScore(old: Int, new: Int): Int = min(100, max(old, new) + 5)
}
