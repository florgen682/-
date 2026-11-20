package app.clipradar.core

import kotlin.math.roundToInt

/**
 * Сигналы, по которым мы решаем, насколько момент интересный.
 * Любой сигнал может быть неизвестен (null) — тогда он просто не учитывается.
 */
data class MomentSignals(
    /** Во сколько раз чат активнее обычного. */
    val chatRatio: Double? = null,
    /** Доля сообщений со смехом/эмоутами (0..1). */
    val hypeShare: Double? = null,
    /** Сколько разных людей писали одновременно. */
    val uniqueChatters: Int? = null,
    /** Насколько громче обычного стал звук (в децибелах). */
    val audioJumpDb: Double? = null,
    /** Сила пика на графике пересмотров YouTube (0..1). */
    val heatmap: Double? = null,
    /** «Обычная» активность чата (сообщений за 10 секунд) — чтобы честно оценивать огромные чаты. */
    val chatBaseline: Double? = null,
)

data class InterestScore(val score: Int, val reason: String)

object InterestScorer {
    private const val W_CHAT = 0.35
    private const val W_HYPE = 0.20
    private const val W_CROWD = 0.20
    private const val W_AUDIO = 0.25
    private const val W_HEAT = 1.0

    private fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)

    /** Оценка 0..100 и понятная причина на русском. */
    fun score(s: MomentSignals): InterestScore {
        data class Part(val weight: Double, val value: Double, val label: String)

        val parts = buildList {
            // В огромном чате всплеск ×3 — это максимум, в обычном — ×6.
            val bigChat = (s.chatBaseline ?: 0.0) >= BurstRule.BIG_CHAT_BASELINE
            val ratioScale = if (bigChat) 2.0 else 5.0
            s.chatRatio?.let { add(Part(W_CHAT, clamp01((it - 1.0) / ratioScale), "чат ×${fmt1(it)}")) }
            s.hypeShare?.let { add(Part(W_HYPE, clamp01(it / 0.4), "смех/эмоуты ${(it * 100).roundToInt()}%")) }
            // В огромном чате «много людей» — это сотни, а не 25.
            val crowdScale = if (bigChat) 150.0 else 25.0
            s.uniqueChatters?.let { add(Part(W_CROWD, clamp01(it / crowdScale), "пишут $it чел.")) }
            s.audioJumpDb?.let { add(Part(W_AUDIO, clamp01(it / 10.0), "звук +${fmt1(maxOf(0.0, it))} дБ")) }
            s.heatmap?.let { add(Part(W_HEAT, clamp01(it), "пик пересмотров")) }
        }
        if (parts.isEmpty()) return InterestScore(0, "нет данных")
        val total = parts.sumOf { it.weight }
        val value = parts.sumOf { it.weight * it.value } / total
        val score = (value * 100).roundToInt().coerceIn(0, 100)
        // В причину пишем самые «сильные» сигналы.
        val reason = parts
            .sortedByDescending { it.weight * it.value }
            .filter { it.value > 0.0 }
            .take(3)
            .joinToString(", ") { it.label }
            .ifEmpty { parts.first().label }
        return InterestScore(score, reason)
    }

    /**
     * Сила пика на графике пересмотров: половина — относительно самого высокого пика,
     * половина — насколько пик выделяется над «средним» уровнем (медианой).
     */
    fun heatmapStrength(value: Double, max: Double, median: Double): Double {
        if (max <= 0) return 0.0
        val relative = value / max
        val prominence = if (max - median > 1e-9) (value - median) / (max - median) else 1.0
        return clamp01(0.5 * relative + 0.5 * clamp01(prominence))
    }

    private fun fmt1(v: Double): String {
        val r = (v * 10).roundToInt() / 10.0
        return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
    }
}
