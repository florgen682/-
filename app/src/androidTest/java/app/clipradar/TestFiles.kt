package app.clipradar

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Помощники автотестов: файлы из assets теста, кадры готового клипа, проверка чёрных полос. */
object TestFiles {
    val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Папка, которую CI забирает с эмулятора (картинки кадров и отчёты). */
    val outDir: File get() = File(ctx.getExternalFilesDir(null), "test-output").apply { mkdirs() }

    fun asset(name: String): File {
        val f = File(ctx.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            f.outputStream().use { input.copyTo(it) }
        }
        return f
    }

    /** Кадр готового клипа в «вертикальном» виде (с учётом поворота из метаданных). */
    fun frameAt(file: File, sec: Double): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var bmp = r.getFrameAtTime((sec * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)!!
            if (bmp.width > bmp.height && rot != 0) {
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            }
            return bmp
        } finally {
            r.release()
        }
    }

    fun save(bmp: Bitmap, name: String) {
        File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Доля строк кадра, которые почти целиком чёрные (так выглядят чёрные поля). */
    fun blackRowFraction(bmp: Bitmap): Double {
        var black = 0
        val row = IntArray(bmp.width)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
            val dark = row.count { p -> ((p shr 16) and 0xFF) < 24 && ((p shr 8) and 0xFF) < 24 && (p and 0xFF) < 24 }
            if (dark >= bmp.width * 0.95) black++
        }
        return black.toDouble() / bmp.height
    }

    /** Средний цвет прямоугольника (доли кадра). */
    fun meanColor(bmp: Bitmap, x0: Double, y0: Double, x1: Double, y1: Double): Triple<Int, Int, Int> {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (y in (y0 * bmp.height).toInt() until (y1 * bmp.height).toInt() step 4) {
            for (x in (x0 * bmp.width).toInt() until (x1 * bmp.width).toInt() step 4) {
                val p = bmp.getPixel(x, y)
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
            }
        }
        return Triple((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    fun report(name: String, text: String) {
        File(outDir, name).appendText(text + "\n")
        android.util.Log.i("ClipRadarTest", text)
    }
}
