package app.clipradar.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import app.clipradar.core.SubtitleCue

/**
 * Текст поверх видео: крупный, белый, с чёрной обводкой.
 * [textAt] получает (время исходника, время итогового клипа) и возвращает текст или null.
 * [anchorY] — где центр текста по вертикали: −1 низ кадра, +1 верх.
 */
@UnstableApi
class TextOverlay(
    private val partStartSec: Double,
    private val outOffsetSec: Double,
    private val anchorY: Float,
    private val textSizePx: Float,
    private val textAt: (srcSec: Double, outSec: Double) -> String?,
) : BitmapOverlay() {
    private var firstPtsUs = Long.MIN_VALUE
    private val cache = HashMap<String, Bitmap>()
    private val empty: Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    private val settings = OverlaySettings.Builder()
        .setBackgroundFrameAnchor(0f, anchorY)
        .build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        if (firstPtsUs == Long.MIN_VALUE) firstPtsUs = presentationTimeUs
        val dt = (presentationTimeUs - firstPtsUs) / 1e6
        val text = textAt(partStartSec + dt, outOffsetSec + dt) ?: return empty
        return cache.getOrPut(text) { render(text) }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    private fun render(text: String): Bitmap {
        val width = 1080
        val maxTextWidth = 960
        val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val stroke = TextPaint(fill).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = textSizePx * 0.16f
            strokeJoin = Paint.Join.ROUND
        }
        // Если слово очень длинное — уменьшаем шрифт, чтобы влезло.
        val longest = text.split(" ").maxOf { fill.measureText(it) }
        if (longest > maxTextWidth) {
            val k = maxTextWidth / longest
            fill.textSize *= k
            stroke.textSize *= k
            stroke.strokeWidth *= k
        }
        val layoutFill = StaticLayout.Builder.obtain(text, 0, text.length, fill, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        val layoutStroke = StaticLayout.Builder.obtain(text, 0, text.length, stroke, maxTextWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        val pad = (stroke.strokeWidth * 2).toInt() + 8
        val bmp = Bitmap.createBitmap(width, layoutFill.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.translate(((width - maxTextWidth) / 2).toFloat(), pad.toFloat())
        layoutStroke.draw(canvas)
        layoutFill.draw(canvas)
        return bmp
    }

    companion object {
        /** Субтитры в нижней трети, но выше подписи и кнопок TikTok/YouTube (центр — 70% высоты сверху). */
        fun subtitles(cues: List<SubtitleCue>, partStartSec: Double, outOffsetSec: Double) =
            TextOverlay(partStartSec, outOffsetSec, anchorY = -0.40f, textSizePx = 84f) { src, _ ->
                cues.firstOrNull { src >= it.start && src < it.end }?.text
            }

        /** Текст-хук первые [seconds] секунд клипа — в верхней части кадра. */
        fun hook(text: String, partStartSec: Double, outOffsetSec: Double, seconds: Double = 2.0) =
            TextOverlay(partStartSec, outOffsetSec, anchorY = 0.62f, textSizePx = 96f) { _, out ->
                if (out < seconds) text else null
            }
    }
}
