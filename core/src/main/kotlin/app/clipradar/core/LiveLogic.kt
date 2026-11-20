package app.clipradar.core

/** План клипа из буфера эфира: какие куски склеить и где внутри находится сам момент. */
data class ClipPlan(
    val segments: List<BufferedSegment>,
    /** Время начала первого куска (по часам телефона). */
    val sourceStartMs: Long,
    /** Через сколько секунд от начала склейки случился момент. */
    val momentOffsetSec: Double,
    val durationSec: Double,
)

object LiveLogic {
    /**
     * Выбирает куски буфера для клипа: [beforeMs] до момента и [afterMs] после.
     * Возвращает null, если в буфере ничего подходящего нет.
     */
    fun planClip(
        segments: List<BufferedSegment>,
        momentMs: Long,
        beforeMs: Long,
        afterMs: Long,
    ): ClipPlan? {
        val chosen = SegmentRing.selectSegments(segments, momentMs - beforeMs, momentMs + afterMs)
        if (chosen.isEmpty()) return null
        val start = chosen.first().startMs
        val duration = chosen.sumOf { it.durationMs } / 1000.0
        return ClipPlan(chosen, start, (momentMs - start) / 1000.0, duration)
    }
}

/** «Пауза между клипами»: не чаще одного момента за [pauseMs]. */
class Cooldown(var pauseMs: Long) {
    private var last: Long? = null

    /** true — можно засчитать новый момент (и пауза начинается заново). */
    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        val l = last
        if (l != null && nowMs - l < pauseMs) return false
        last = nowMs
        return true
    }

    @Synchronized
    fun remainingMs(nowMs: Long): Long {
        val l = last ?: return 0
        return maxOf(0, pauseMs - (nowMs - l))
    }
}
