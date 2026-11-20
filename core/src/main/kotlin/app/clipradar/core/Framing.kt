package app.clipradar.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** Прямоугольник в долях кадра (0..1), ось Y — вниз (как в картинке). */
data class NRect(val x: Double, val y: Double, val w: Double, val h: Double) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
    val right get() = x + w
    val bottom get() = y + h
    fun inside(eps: Double = 1e-6) = x >= -eps && y >= -eps && right <= 1 + eps && bottom <= 1 + eps
}

/** Лицо на кадре в момент времени t (секунды от начала клипа/окна). */
data class FaceBox(val t: Double, val box: NRect)

/** Что видно на кадре в момент t: лица и «центр движения» по горизонтали. */
data class FrameSample(val t: Double, val faces: List<FaceBox> = emptyList(), val motionX: Double? = null)

/** Точка «траектории» центра обрезки. */
data class CropKey(val t: Double, val cx: Double)

/** Раскладка «вебка сверху, игра снизу». */
data class StreamLayout(val cam: NRect, val game: NRect, val camFraction: Double)

/**
 * Кадрирование вертикального клипа без чёрных полей:
 *  - «Заполнить»: увеличиваем видео на весь экран 9:16 и плавно ведём центр за лицом или действием;
 *  - «Стрим-раскладка»: вебка сверху, игра снизу, обе — на всю ширину.
 */
object CropPlanner {
    /** Размер области обрезки (в долях кадра), чтобы заполнить кадр [outAspect] целиком. */
    fun fillCropSize(inAspect: Double, outAspect: Double): Pair<Double, Double> =
        if (inAspect >= outAspect) (outAspect / inAspect) to 1.0 else 1.0 to (inAspect / outAspect)

    /**
     * Плавная траектория центра обрезки: за лицом (если есть) или за движением.
     * Без дёрганья: мёртвая зона, ограничение скорости и сглаживание вперёд-назад.
     */
    fun smartTrack(
        samples: List<FrameSample>,
        cropW: Double,
        followFaces: Boolean = true,
        maxSpeed: Double = 0.25,
        deadZone: Double = 0.04,
    ): List<CropKey> {
        val half = cropW / 2
        val lo = half
        val hi = 1 - half
        if (samples.isEmpty() || cropW >= 1.0) return listOf(CropKey(0.0, 0.5))
        val sorted = samples.sortedBy { it.t }
        // 1) Цель на каждом кадре: самое крупное лицо → центр движения → предыдущая цель.
        var last = 0.5
        val raw = sorted.map { s ->
            val face = if (followFaces) s.faces.maxByOrNull { it.box.w * it.box.h } else null
            last = face?.box?.cx ?: s.motionX ?: last
            last
        }
        // 2) Медианный фильтр убирает случайные выбросы.
        val med = raw.indices.map { i ->
            raw.subList(max(0, i - 2), min(raw.size, i + 3)).sorted().let { it[it.size / 2] }
        }
        // 3) Следуем с мёртвой зоной и ограничением скорости.
        val path = DoubleArray(sorted.size)
        var pos = med.first().coerceIn(lo, hi)
        for (i in sorted.indices) {
            val dt = if (i == 0) 0.0 else sorted[i].t - sorted[i - 1].t
            val target = med[i].coerceIn(lo, hi)
            val diff = target - pos
            if (abs(diff) > deadZone) pos += sign(diff) * min(abs(diff) - deadZone, maxSpeed * dt)
            path[i] = pos
        }
        // 4) Сглаживание в обе стороны — плавный старт и остановка.
        val a = 0.35
        for (i in 1 until path.size) path[i] = path[i - 1] + a * (path[i] - path[i - 1])
        for (i in path.size - 2 downTo 0) path[i] = path[i + 1] + a * (path[i] - path[i + 1])
        return sorted.indices.map { CropKey(sorted[it].t, path[it].coerceIn(lo, hi)) }
    }

    /** Центр обрезки в момент t (линейно между точками траектории). */
    fun centerAt(track: List<CropKey>, t: Double): Double {
        if (track.isEmpty()) return 0.5
        if (t <= track.first().t) return track.first().cx
        if (t >= track.last().t) return track.last().cx
        var lo = 0
        var hi = track.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (track[mid].t <= t) lo = mid else hi = mid
        }
        val a = track[lo]
        val b = track[hi]
        val f = if (b.t > a.t) (t - a.t) / (b.t - a.t) else 0.0
        return a.cx + (b.cx - a.cx) * f
    }

    /**
     * Есть ли в кадре вебкамера стримера: небольшое лицо, которое почти всё время стоит
     * на одном месте и не в центре кадра. Возвращает примерную рамку вебки.
     */
    fun detectWebcam(samples: List<FrameSample>): NRect? {
        val faces = samples.flatMap { it.faces }
        if (samples.isEmpty() || faces.isEmpty()) return null
        // Группируем лица по положению.
        val clusters = ArrayList<MutableList<FaceBox>>()
        for (f in faces.filter { it.box.w < 0.22 }) {
            val c = clusters.firstOrNull { cl ->
                val m = cl.first().box
                abs(m.cx - f.box.cx) < 0.06 && abs(m.cy - f.box.cy) < 0.08
            }
            if (c != null) c += f else clusters += mutableListOf(f)
        }
        val best = clusters.maxByOrNull { cl -> cl.map { it.t }.distinct().size } ?: return null
        val presence = best.map { it.t }.distinct().size.toDouble() / samples.size
        if (presence < 0.5) return null
        val cx = PeakFinder.median(best.map { it.box.cx })
        val cy = PeakFinder.median(best.map { it.box.cy })
        val fw = PeakFinder.median(best.map { it.box.w })
        val fh = PeakFinder.median(best.map { it.box.h })
        // Лицо в самом центре — это не вебка, а «just chatting» / IRL.
        if (abs(cx - 0.5) < 0.18 && abs(cy - 0.5) < 0.2) return null
        return estimateCamRect(cx, cy, fw, fh)
    }

    /** Примерная рамка вебки по лицу: лицо занимает около трети ширины камеры. */
    fun estimateCamRect(cx: Double, cy: Double, fw: Double, fh: Double): NRect {
        val w = min(0.6, fw * 3.0)
        val h = min(0.6, fh * 2.8)
        val x = (cx - w / 2).coerceIn(0.0, 1 - w)
        val y = (cy + fh * 0.15 - h / 2).coerceIn(0.0, 1 - h)
        return NRect(x, y, w, h)
    }

    /**
     * Уточняет рамку вебки по резким границам на кадре (рамка оверлея обычно видна как прямая линия).
     * [luma] — яркость кадров (0..255) размером width×height; можно передать несколько кадров —
     * граница вебки неподвижна, а игра меняется, поэтому она проявляется сильнее.
     */
    fun refineCamRect(guess: NRect, face: NRect, lumas: List<IntArray>, width: Int, height: Int): NRect {
        if (lumas.isEmpty()) return guess
        fun colEdge(x: Int, y0: Int, y1: Int): Double {
            var s = 0.0
            var n = 0
            for (l in lumas) for (y in y0 until y1) {
                s += abs(l[y * width + x] - l[y * width + x - 1]); n++
            }
            return if (n == 0) 0.0 else s / n
        }
        fun rowEdge(y: Int, x0: Int, x1: Int): Double {
            var s = 0.0
            var n = 0
            for (l in lumas) for (x in x0 until x1) {
                s += abs(l[y * width + x] - l[(y - 1) * width + x]); n++
            }
            return if (n == 0) 0.0 else s / n
        }
        fun bestLine(range: IntRange, f: (Int) -> Double): Int? {
            val vals = range.filter { it > 0 }.map { it to f(it) }
            if (vals.size < 3) return null
            val med = PeakFinder.median(vals.map { it.second })
            val top = vals.maxByOrNull { it.second } ?: return null
            return if (top.second > 14 && top.second > med * 2.5) top.first else null
        }
        val fx0 = (face.x * width).toInt()
        val fx1 = (face.right * width).toInt()
        val fy0 = (face.y * height).toInt()
        val fy1 = (face.bottom * height).toInt()
        val fwPx = max(2, fx1 - fx0)
        val fhPx = max(2, fy1 - fy0)
        val bandY0 = max(1, fy0 - fhPx)
        val bandY1 = min(height, fy1 + fhPx)
        val bandX0 = max(1, fx0 - fwPx)
        val bandX1 = min(width, fx1 + fwPx)
        val left = bestLine(max(1, fx0 - 3 * fwPx)..max(1, fx0 - fwPx / 4)) { colEdge(it, bandY0, bandY1) }
        val right = bestLine(min(width - 1, fx1 + fwPx / 4)..min(width - 1, fx1 + 3 * fwPx)) { colEdge(it, bandY0, bandY1) }
        val top = bestLine(max(1, fy0 - 3 * fhPx)..max(1, fy0 - fhPx / 4)) { rowEdge(it, bandX0, bandX1) }
        val bottom = bestLine(min(height - 1, fy1 + fhPx / 4)..min(height - 1, fy1 + 3 * fhPx)) { rowEdge(it, bandX0, bandX1) }
        val x0 = left?.let { it.toDouble() / width } ?: guess.x
        val x1 = right?.let { it.toDouble() / width } ?: guess.right
        val y0 = top?.let { it.toDouble() / height } ?: guess.y
        val y1 = bottom?.let { it.toDouble() / height } ?: guess.bottom
        if (x1 - x0 < face.w * 1.2 || y1 - y0 < face.h * 1.2) return guess
        return NRect(x0, y0, x1 - x0, y1 - y0)
    }

    /**
     * Раскладка «вебка сверху (camFraction высоты), игра снизу». Обе части заполняют
     * свою половину целиком (обрезка по краям), поэтому чёрных полей нет.
     */
    fun streamLayout(inAspect: Double, cam: NRect, face: NRect?, outW: Int = 1080, outH: Int = 1920, camFraction: Double = 0.4): StreamLayout {
        val camPanel = outW.toDouble() / (outH * camFraction) // соотношение сторон верхней части
        val gamePanel = outW.toDouble() / (outH * (1 - camFraction))
        val camRect = fitInside(cam, camPanel, inAspect, face?.cx ?: cam.cx, face?.cy ?: cam.cy)
        // Игра: вся высота кадра, ширина по пропорции нижней части, по центру (сдвигаемся от вебки).
        val (gw, gh) = fillCropSize(inAspect, gamePanel)
        var gx = 0.5 - gw / 2
        if (cam.right > gx && cam.x < gx + gw && cam.h < 0.6) {
            // Вебка заходит в зону игры — сдвигаем игру в противоположную сторону, если есть место.
            gx = if (cam.cx < 0.5) min(1 - gw, max(gx, cam.right)) else max(0.0, min(gx, cam.x - gw))
        }
        return StreamLayout(camRect, NRect(gx.coerceIn(0.0, 1 - gw), (1 - gh) / 2, gw, gh), camFraction)
    }

    /** Максимальная область внутри [r] с нужной пропорцией [panelAspect] (в пикселях), с центром у (cx, cy). */
    fun fitInside(r: NRect, panelAspect: Double, inAspect: Double, cx: Double, cy: Double): NRect {
        // Пропорция в пикселях = (w/h) * inAspect.
        val rectAspect = (r.w / r.h) * inAspect
        val (w, h) = if (rectAspect > panelAspect) (r.h * panelAspect / inAspect) to r.h else r.w to (r.w * inAspect / panelAspect)
        val x = (cx - w / 2).coerceIn(r.x, r.right - w)
        val y = (cy - h / 2).coerceIn(r.y, r.bottom - h)
        return NRect(x, y, w, h)
    }

    /**
     * Проверка «нет чёрных полей»: каждая точка итогового кадра берётся изнутри исходника,
     * а пропорции совпадают (картинка не сжата). [regions] — пары (часть выходного кадра, откуда берём).
     */
    fun coversWithoutBars(regions: List<Pair<NRect, NRect>>, inAspect: Double, outAspect: Double, tolerance: Double = 0.01): Boolean {
        // Части выходного кадра должны покрывать его целиком (площадь = 1, без дыр по вертикали).
        val area = regions.sumOf { it.first.w * it.first.h }
        if (abs(area - 1.0) > tolerance) return false
        for ((out, src) in regions) {
            if (!src.inside(tolerance) || !out.inside(tolerance)) return false
            val outPx = (out.w / out.h) * outAspect
            val srcPx = (src.w / src.h) * inAspect
            if (abs(outPx - srcPx) / outPx > tolerance) return false
        }
        return true
    }
}

/** Качество клипа: сколько в нём тишины и «статичной» картинки (пауза, загрузка, меню). */
object QualityCheck {
    const val SILENT_DB = -50.0
    const val STATIC_DIFF = 1.5

    /**
     * Доля «плохих» секунд. [loudnessDb] — громкость по секундам, [frameDiff] — средняя разница
     * соседних кадров по секундам (0..255); null — данных нет.
     */
    fun badFraction(loudnessDb: List<Double>, frameDiff: List<Double?>): Double {
        val n = max(loudnessDb.size, frameDiff.size)
        if (n == 0) return 0.0
        var bad = 0
        for (i in 0 until n) {
            val silent = loudnessDb.getOrNull(i)?.let { it < SILENT_DB } ?: false
            val static = frameDiff.getOrNull(i)?.let { it < STATIC_DIFF } ?: false
            if (silent || static) bad++
        }
        return bad.toDouble() / n
    }

    fun isBad(fraction: Double) = fraction > 0.4
}

/** «Хороший / плохой момент»: подстройка порога для каждого стримера. */
object FeedbackTuner {
    /** Новый сдвиг порога после оценки: плохой → строже (+4), хороший → мягче (−3). */
    fun adjust(current: Int, good: Boolean): Int = (current + if (good) -3 else 4).coerceIn(-20, 20)

    fun threshold(base: Int, adjust: Int): Int = (base + adjust).coerceIn(0, 100)

    /** Чем больше «плохих», тем сильнее должен взорваться чат. */
    fun chatMultiplierScale(adjust: Int): Double = 1.0 + adjust * 0.02
}
