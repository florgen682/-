package app.clipradar.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

/**
 * Громкость по стандарту EBU R128 / ITU-R BS.1770 (LUFS) — так её меряют YouTube и TikTok.
 * Фильтр K-weighting + блоки по 400 мс + «ворота» (−70 LUFS и −10 LU).
 */
class LufsMeter(private val sampleRate: Int, private val channels: Int) {
    private val f1 = Biquad.highShelf(sampleRate)
    private val f2 = Biquad.highPass(sampleRate)
    private val filters1 = Array(channels) { f1.copy() }
    private val filters2 = Array(channels) { f2.copy() }
    private val hop = max(1, sampleRate / 10) // 100 мс
    private val subBlocks = ArrayList<Double>() // сумма квадратов за каждые 100 мс (по всем каналам)
    private val subPeaks = ArrayList<Double>()  // пик за каждые 100 мс
    private var hopPeak = 0.0
    private var acc = 0.0
    private var inHop = 0
    private var ch = 0
    var peak = 0.0
        private set

    /** Сэмплы по очереди (L, R, L, R…), в диапазоне −1..1. */
    fun feed(sample: Float) {
        val x = sample.toDouble()
        if (abs(x) > peak) peak = abs(x)
        if (abs(x) > hopPeak) hopPeak = abs(x)
        val y = filters2[ch].process(filters1[ch].process(x))
        acc += y * y
        ch++
        if (ch == channels) {
            ch = 0
            inHop++
            if (inHop == hop) {
                subBlocks += acc / hop
                subPeaks += hopPeak
                hopPeak = 0.0
                acc = 0.0
                inHop = 0
            }
        }
    }

    /** Итоговая громкость, LUFS (или null, если звука почти нет). */
    fun integrated(): Double? = integratedRange(0, subBlocks.size)

    /** Громкость только отрезка [fromSec, toSec] (например, итогового клипа). */
    fun integrated(fromSec: Double, toSec: Double): Double? =
        integratedRange((fromSec * 10).toInt().coerceAtLeast(0), (toSec * 10).toInt().coerceAtMost(subBlocks.size))

    /** Пик сэмплов в отрезке [fromSec, toSec]. */
    fun peak(fromSec: Double, toSec: Double): Double {
        val a = (fromSec * 10).toInt().coerceIn(0, subPeaks.size)
        val b = (toSec * 10).toInt().coerceIn(a, subPeaks.size)
        return if (b <= a) peak else subPeaks.subList(a, b).maxOrNull() ?: peak
    }

    private fun integratedRange(from: Int, to: Int): Double? {
        if (to - from < 4) return null
        // Блоки по 400 мс с перекрытием 75% = 4 соседних отрезка по 100 мс.
        val blocks = (from..to - 4).map { i -> (subBlocks[i] + subBlocks[i + 1] + subBlocks[i + 2] + subBlocks[i + 3]) / 4 }
        fun lufs(power: Double) = -0.691 + 10 * log10(max(power, 1e-12))
        val abs = blocks.filter { lufs(it) > -70.0 }
        if (abs.isEmpty()) return null
        val relGate = lufs(abs.average()) - 10.0
        val gated = abs.filter { lufs(it) > relGate }
        if (gated.isEmpty()) return null
        return lufs(gated.average())
    }

    companion object {
        /**
         * Во сколько раз изменить громкость, чтобы получить [targetLufs] (обычно −14),
         * не допуская пиков выше [maxPeakDb] и усиления больше [maxGainDb].
         */
        fun gainFor(measuredLufs: Double?, peak: Double, targetLufs: Double = -14.0, maxPeakDb: Double = -1.0, maxGainDb: Double = 20.0): Double {
            if (measuredLufs == null) return 1.0
            var gainDb = (targetLufs - measuredLufs).coerceIn(-30.0, maxGainDb)
            if (peak > 0) {
                val peakDb = 20 * log10(peak)
                gainDb = min(gainDb, maxPeakDb - peakDb)
            }
            return 10.0.pow(gainDb / 20)
        }
    }
}

/** Простой цифровой фильтр второго порядка. */
data class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }

    companion object {
        // Коэффициенты K-weighting для любой частоты дискретизации (как в libebur128).
        fun highShelf(fs: Int): Biquad {
            val f0 = 1681.974450955533
            val g = 3.999843853973347
            val q = 0.7071752369554196
            val k = tan(PI * f0 / fs)
            val vh = 10.0.pow(g / 20)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            return Biquad(
                (vh + vb * k / q + k * k) / a0, 2 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0,
                2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0,
            )
        }

        fun highPass(fs: Int): Biquad {
            val f0 = 38.13547087602444
            val q = 0.5003270373238773
            val k = tan(PI * f0 / fs)
            val a0 = 1 + k / q + k * k
            return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }
    }
}

/** Перевод звука в 16 кГц моно — такой формат нужен Whisper. */
class MonoResampler(private val inRate: Int, private val channels: Int, private val outRate: Int = 16_000) {
    private val out = FloatArrayBuilder()
    private var frameSum = 0f
    private var ch = 0
    private var inPos = 0L // номер входного кадра
    private var prev = 0f
    private var nextOut = 0.0 // позиция следующего выходного сэмпла во входных кадрах
    private val ratio = inRate.toDouble() / outRate

    fun feed(sample: Float) {
        frameSum += sample
        ch++
        if (ch < channels) return
        val mono = frameSum / channels
        frameSum = 0f
        ch = 0
        // Линейная интерполяция между предыдущим и текущим входным кадром.
        while (nextOut <= inPos) {
            val frac = (nextOut - (inPos - 1)).toFloat().coerceIn(0f, 1f)
            out.add(prev + (mono - prev) * frac)
            nextOut += ratio
        }
        prev = mono
        inPos++
    }

    fun result(): FloatArray = out.toArray()
}

class FloatArrayBuilder(initial: Int = 1 shl 16) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
