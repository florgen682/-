package app.clipradar.core

/** Точка графика: момент времени (в секундах) и значение (пересмотры, громкость и т.п.). */
data class Point(val timeSec: Double, val value: Double)

/** Кусок видео, который нужно вырезать. */
data class TimeRange(val startSec: Double, val endSec: Double) {
    val durationSec: Double get() = endSec - startSec
}

object PeakFinder {

    /**
     * Находит [count] самых высоких точек, которые находятся не ближе [minDistanceSec]
     * друг к другу. Работает «жадно»: берём самую высокую точку, потом следующую по высоте,
     * которая достаточно далеко от уже выбранных, и так далее.
     *
     * Результат отсортирован по значению — лучшие сверху.
     */
    fun findTopPeaks(
        points: List<Point>,
        count: Int = 5,
        minDistanceSec: Double = 60.0,
        ignoreBeforeSec: Double = 0.0,
    ): List<Point> {
        if (count <= 0) return emptyList()
        val candidates = points
            .filter { it.timeSec >= ignoreBeforeSec && !it.value.isNaN() }
            .sortedWith(compareByDescending<Point> { it.value }.thenBy { it.timeSec })
        val chosen = ArrayList<Point>(count)
        for (p in candidates) {
            if (chosen.all { kotlin.math.abs(it.timeSec - p.timeSec) >= minDistanceSec }) {
                chosen += p
                if (chosen.size == count) break
            }
        }
        return chosen
    }

    /**
     * Превращает график YouTube «самые пересматриваемые» (heatmap) в точки:
     * время = середина отрезка.
     */
    fun heatmapToPoints(segments: List<HeatmapSegment>): List<Point> =
        segments.map { Point((it.startSec + it.endSec) / 2.0, it.value) }

    /** Скользящее среднее — сглаживает «шум», чтобы одиночный щелчок не считался пиком. */
    fun smooth(values: List<Double>, window: Int): List<Double> {
        if (window <= 1 || values.isEmpty()) return values
        val half = window / 2
        return values.indices.map { i ->
            val from = maxOf(0, i - half)
            val to = minOf(values.lastIndex, i + half)
            var sum = 0.0
            for (j in from..to) sum += values[j]
            sum / (to - from + 1)
        }
    }

    /**
     * Отрезок вокруг пика: [before] секунд до и [after] секунд после.
     * Не выходит за начало и конец видео.
     */
    fun rangeAround(peakSec: Double, before: Double, after: Double, durationSec: Double?): TimeRange {
        var start = peakSec - before
        var end = peakSec + after
        if (start < 0) {
            end -= start
            start = 0.0
        }
        if (durationSec != null && durationSec > 0 && end > durationSec) {
            start = maxOf(0.0, start - (end - durationSec))
            end = durationSec
        }
        return TimeRange(start, end)
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
    }
}

data class HeatmapSegment(val startSec: Double, val endSec: Double, val value: Double)
