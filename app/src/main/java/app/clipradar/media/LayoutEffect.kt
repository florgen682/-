package app.clipradar.media

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import app.clipradar.core.CropKey
import app.clipradar.core.CropPlanner
import app.clipradar.core.NRect

/**
 * Как разложить исходный кадр в вертикальный 1080×1920.
 *  - [cam] == null → «Заполнить»: область [cropW]×[cropH] на весь экран, центр по траектории [track];
 *  - [cam] != null → «Стрим-раскладка»: сверху вебка ([camFraction] высоты), снизу игра ([game] + [gameTrack]).
 * Все прямоугольники — в долях исходного кадра (ось Y вниз). Траектории — по времени исходника (секунды).
 */
data class FramePlan(
    val cropW: Double,
    val cropH: Double,
    val track: List<CropKey>,
    val cam: NRect? = null,
    val game: NRect? = null,
    val gameTrack: List<CropKey> = emptyList(),
    val camFraction: Double = 0.4,
)

/** Эффект Media3 с раскладкой кадра. [partStartSec] — с какой секунды исходника начинается эта часть клипа. */
@UnstableApi
class LayoutEffect(
    private val plan: FramePlan,
    private val partStartSec: Double,
    private val outWidth: Int = 1080,
    private val outHeight: Int = 1920,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LayoutShaderProgram(plan, partStartSec, outWidth, outHeight, useHdr)
}

@UnstableApi
private class LayoutShaderProgram(
    private val plan: FramePlan,
    private val partStartSec: Double,
    private val outWidth: Int,
    private val outHeight: Int,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, 1) {

    private val program: GlProgram = try {
        GlProgram(VERTEX, FRAGMENT)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var firstPtsUs = Long.MIN_VALUE

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size = Size(outWidth, outHeight)

    /** Прямоугольник картинки (Y вниз) → координаты текстуры OpenGL (Y вверх): x, y, w, h. */
    private fun tex(r: NRect) = floatArrayOf(r.x.toFloat(), (1.0 - r.bottom).toFloat(), r.w.toFloat(), r.h.toFloat())

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        if (firstPtsUs == Long.MIN_VALUE) firstPtsUs = presentationTimeUs
        val t = partStartSec + (presentationTimeUs - firstPtsUs) / 1e6
        val top: FloatArray
        val bottom: FloatArray
        val split: Float
        val cam = plan.cam
        val game = plan.game
        if (cam != null && game != null) {
            val gx = if (plan.gameTrack.isEmpty()) game.x else CropPlanner.centerAt(plan.gameTrack, t) - game.w / 2
            top = tex(cam)
            bottom = tex(game.copy(x = gx.coerceIn(0.0, 1 - game.w)))
            split = plan.camFraction.toFloat()
        } else {
            val cx = CropPlanner.centerAt(plan.track, t)
            val r = NRect((cx - plan.cropW / 2).coerceIn(0.0, 1 - plan.cropW), (1 - plan.cropH) / 2, plan.cropW, plan.cropH)
            top = tex(r)
            bottom = top
            split = 0f
        }
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uRectTop", top)
            program.setFloatsUniform("uRectBottom", bottom)
            program.setFloatUniform("uSplit", split)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        // uRect* = (x, y, w, h) в координатах текстуры. uSplit — доля высоты верхней части (0 — одна область).
        private const val FRAGMENT = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec4 uRectTop;
uniform vec4 uRectBottom;
uniform float uSplit;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 uv = vTexSamplingCoord;
  float topStart = 1.0 - uSplit;
  vec4 r;
  vec2 local;
  if (uSplit > 0.0 && uv.y >= topStart) {
    r = uRectTop;
    local = vec2(uv.x, (uv.y - topStart) / uSplit);
  } else {
    r = uRectBottom;
    local = vec2(uv.x, uSplit > 0.0 ? uv.y / topStart : uv.y);
  }
  gl_FragColor = texture2D(uTexSampler, r.xy + local * r.zw);
}
"""
    }
}
