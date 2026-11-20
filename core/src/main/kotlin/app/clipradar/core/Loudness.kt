package app.clipradar.core

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Считает громкость звука по секундам. Ему «скармливают» звук (PCM-сэмплы),
 * а он выдаёт громкость каждой секунды в децибелах.
 */
class LoudnessMeter(sampleRate: Int, private val channels: Int) {
    private val samplesPerSecond = maxOf(1, sampleRate) * maxOf(1, channels)
    private var sumSquares = 0.0
    private var count = 0
    private val result = ArrayList<Double>()

    /** Сэмплы в диапазоне -1..1. */
    fun feed(sample: Float) {
        sumSquares += sample.toDouble() * sample
        count++
        if (count >= samplesPerSecond) flushSecond()
    }

    fun feedPcm16(data: ShortArray, length: Int = data.size) {
        for (i in 0 until length) feed(data[i] / 32768f)
    }

    private fun flushSecond() {
        result += Loudness.rmsToDb(sqrt(sumSquares / maxOf(1, count)))
        sumSquares = 0.0
        count = 0
    }

    /** Громкость по секундам (последняя неполная секунда тоже учитывается, если её больше половины). */
    fun finish(): List<Double> {
        if (count > samplesPerSecond / 2) flushSecond()
        return result.toList()
    }
}

object Loudness {
    fun rmsToDb(rms: Double): Double = if (rms <= 1e-6) -120.0 else 20.0 * log10(rms)

    /** Самые громкие места: сглаживаем по 3 секунды и выбираем пики не ближе [minDistanceSec]. */
    fun loudestPeaks(dbPerSecond: List<Double>, count: Int = 5, minDistanceSec: Double = 60.0): List<Point> {
        val smoothed = PeakFinder.smooth(dbPerSecond, 3)
        val points = smoothed.mapIndexed { i, v -> Point(i + 0.5, v) }
        return PeakFinder.findTopPeaks(points, count, minDistanceSec)
    }

    /** Насколько пик (сглаженный) громче «обычного» уровня — медианы всех секунд. */
    fun jumpDb(dbPerSecond: List<Double>, peakSec: Double, before: Int = 8, after: Int = 4): Double? {
        if (dbPerSecond.size < 3) return null
        val smoothed = PeakFinder.smooth(dbPerSecond, 3)
        val center = peakSec.toInt()
        val from = (center - before).coerceIn(0, smoothed.lastIndex)
        val to = (center + after).coerceIn(0, smoothed.lastIndex)
        if (from > to) return null
        val peak = (from..to).maxOf { smoothed[it] }
        // Тишину (-120 дБ) не учитываем в «норме», иначе любой звук покажется скачком.
        val audible = dbPerSecond.filter { it > -90.0 }
        if (audible.isEmpty()) return 0.0
        return maxOf(0.0, peak - PeakFinder.median(audible))
    }
}
