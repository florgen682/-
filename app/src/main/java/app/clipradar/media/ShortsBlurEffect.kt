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

/**
 * Эффект «как в Shorts»: видео по центру во всю ширину,
 * а сверху и снизу — увеличенная размытая копия того же кадра.
 *
 * Работает на видеокарте в два прохода (так размытие получается гладким и быстрым):
 *  1) строим кадр 1080x1920: в центре резкое видео, по краям — увеличенный кадр,
 *     размытый по горизонтали;
 *  2) размываем края по вертикали и немного затемняем, чтобы видео в центре «выделялось».
 */
@UnstableApi
class ShortsBlurEffect(
    private val pass: Int,
    /** Пропорции исходного видео (ширина / высота), например 16/9. */
    private val sourceAspect: Float,
    private val outWidth: Int = 1080,
    private val outHeight: Int = 1920,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ShortsBlurShaderProgram(pass, sourceAspect, outWidth, outHeight, useHdr)

    companion object {
        /** Оба прохода — добавляются в список эффектов по порядку. */
        fun both(sourceAspect: Float, outWidth: Int = 1080, outHeight: Int = 1920) = listOf(
            ShortsBlurEffect(1, sourceAspect, outWidth, outHeight),
            ShortsBlurEffect(2, sourceAspect, outWidth, outHeight),
        )
    }
}

@UnstableApi
private class ShortsBlurShaderProgram(
    private val pass: Int,
    private val sourceAspect: Float,
    private val outWidth: Int,
    private val outHeight: Int,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, 1) {

    private val program: GlProgram = try {
        GlProgram(VERTEX, if (pass == 1) FRAGMENT_PASS1 else FRAGMENT_PASS2)
    } catch (e: GlUtil.GlException) {
        throw VideoFrameProcessingException(e)
    }
    private var fgSize = floatArrayOf(1f, 1f)
    private var bgScale = floatArrayOf(1f, 1f)
    private var blurStep = floatArrayOf(0f, 0f)

    init {
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val outAspect = outWidth.toFloat() / outHeight
        // В первом проходе знаем настоящий размер кадра, во втором — берём переданные пропорции.
        val inAspect = if (pass == 1 && inputHeight > 0) inputWidth.toFloat() / inputHeight else sourceAspect
        // Резкое видео: вписываем целиком (по ширине для горизонтальных роликов).
        fgSize = if (inAspect >= outAspect) floatArrayOf(1f, outAspect / inAspect)
        else floatArrayOf(inAspect / outAspect, 1f)
        // Фон: заполняем весь кадр с обрезкой краёв (увеличенная копия).
        bgScale = if (inAspect >= outAspect) floatArrayOf(outAspect / inAspect, 1f)
        else floatArrayOf(1f, inAspect / outAspect)
        // Радиус размытия ~100 пикселей итогового кадра.
        blurStep = if (pass == 1) floatArrayOf(BLUR_RADIUS_PX / outWidth * bgScale[0] / TAPS_HALF, 0f)
        else floatArrayOf(0f, BLUR_RADIUS_PX / outHeight / TAPS_HALF)
        return Size(outWidth, outHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uFgSize", fgSize)
            program.setFloatsUniform("uBgScale", bgScale)
            program.setFloatsUniform("uStep", blurStep)
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
        private const val BLUR_RADIUS_PX = 100f
        private const val TAPS_HALF = 10f

        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

        // Общая часть: 21 отсчёт с весами по Гауссу.
        private const val BLUR_LOOP = """
  vec4 sum = vec4(0.0);
  float wsum = 0.0;
  for (int i = -10; i <= 10; i++) {
    float fi = float(i);
    float w = exp(-(fi * fi) / 50.0);
    sum += texture2D(uTexSampler, clamp(base + uStep * fi, lo, hi)) * w;
    wsum += w;
  }
  vec4 blurred = sum / wsum;
"""

        private const val HEADER = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uFgSize;
uniform vec2 uBgScale;
uniform vec2 uStep;
varying vec2 vTexSamplingCoord;
"""

        private const val FRAGMENT_PASS1 = HEADER + """
void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 d = uv - 0.5;
  vec2 halfFg = uFgSize * 0.5;
  if (abs(d.x) <= halfFg.x && abs(d.y) <= halfFg.y) {
    gl_FragColor = texture2D(uTexSampler, d / uFgSize + 0.5);
    return;
  }
  vec2 base = d * uBgScale + 0.5;
  vec2 lo = vec2(0.0);
  vec2 hi = vec2(1.0);
""" + BLUR_LOOP + """
  gl_FragColor = vec4(blurred.rgb, 1.0);
}
"""

        private const val FRAGMENT_PASS2 = HEADER + """
void main() {
  vec2 uv = vTexSamplingCoord;
  vec2 d = uv - 0.5;
  vec2 halfFg = uFgSize * 0.5;
  if (abs(d.x) <= halfFg.x && abs(d.y) <= halfFg.y) {
    gl_FragColor = texture2D(uTexSampler, uv);
    return;
  }
  vec2 base = uv;
  // Не «заглядываем» в резкое видео, чтобы на границе не было ореола.
  float top = 0.5 + halfFg.y + 0.002;
  float bottom = 0.5 - halfFg.y - 0.002;
  vec2 lo = vec2(0.0, uv.y > 0.5 ? top : 0.0);
  vec2 hi = vec2(1.0, uv.y > 0.5 ? 1.0 : bottom);
""" + BLUR_LOOP + """
  gl_FragColor = vec4(blurred.rgb * 0.6, 1.0);
}
"""
    }
}
