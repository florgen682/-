package app.clipradar.core

import kotlin.math.abs

/** Кусочек прямого эфира (обычно 2 секунды), сохранённый в файл. */
data class BufferedSegment(
    val sequence: Long,
    val file: String,
    val durationMs: Long,
    /** Когда (по часам телефона) этот кусок начинается. */
    val startMs: Long,
) {
    val endMs: Long get() = startMs + durationMs
}

/** Новый скачанный кусочек — ещё без привязки ко времени. */
data class NewSegment(val sequence: Long, val file: String, val durationMs: Long)

/**
 * Кольцевой буфер последних N минут эфира.
 *
 * Каждому куску мы назначаем время «по часам телефона» — так его можно сопоставить
 * с сообщениями чата, которые тоже приходят «по часам телефона».
 */
class SegmentRing(private val keepMs: Long = 180_000) {
    private val segments = ArrayList<BufferedSegment>()

    val size: Int @Synchronized get() = segments.size

    @Synchronized
    fun all(): List<BufferedSegment> = segments.toList()

    /**
     * Добавляет пачку кусков, которые только что скачались в момент [arrivalMs].
     * Последний кусок заканчивается в [arrivalMs], предыдущие — раньше.
     * Если новые куски продолжают старые без большого разрыва, склеиваем «встык».
     */
    @Synchronized
    fun addBatch(batch: List<NewSegment>, arrivalMs: Long): List<BufferedSegment> {
        val fresh = batch
            .filter { s -> segments.none { it.sequence == s.sequence } }
            .sortedBy { it.sequence }
        if (fresh.isEmpty()) return emptyList()
        val totalMs = fresh.sumOf { it.durationMs }
        var start = arrivalMs - totalMs
        val last = segments.lastOrNull()
        if (last != null && abs(start - last.endMs) <= CONTINUITY_TOLERANCE_MS) {
            start = last.endMs
        }
        val added = fresh.map { s ->
            BufferedSegment(s.sequence, s.file, s.durationMs, start).also { start += s.durationMs }
        }
        segments += added
        return added
    }

    /** Удаляет куски старше [keepMs]; возвращает удалённые, чтобы стереть их файлы. */
    @Synchronized
    fun prune(nowMs: Long): List<BufferedSegment> {
        val limit = nowMs - keepMs
        val old = segments.filter { it.endMs < limit }
        segments.removeAll(old.toSet())
        return old
    }

    /** Куски, которые хотя бы частично попадают в отрезок [fromMs, toMs]. */
    @Synchronized
    fun select(fromMs: Long, toMs: Long): List<BufferedSegment> = selectSegments(segments, fromMs, toMs)

    @Synchronized
    fun clear(): List<BufferedSegment> {
        val all = segments.toList()
        segments.clear()
        return all
    }

    companion object {
        const val CONTINUITY_TOLERANCE_MS = 5_000L

        fun selectSegments(segments: List<BufferedSegment>, fromMs: Long, toMs: Long): List<BufferedSegment> =
            segments.filter { it.endMs > fromMs && it.startMs < toMs }.sortedBy { it.sequence }
    }
}
