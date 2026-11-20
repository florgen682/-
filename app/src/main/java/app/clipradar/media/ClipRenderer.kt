package app.clipradar.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.clipradar.core.SubtitleCue
import app.clipradar.core.TimeRange
import app.clipradar.data.ClipLayout
import app.clipradar.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Всё, что нужно, чтобы сделать клип. Время — секунды внутри файла-исходника. */
data class RenderPlan(
    /** Части клипа по порядку (с «хуком» — сначала кусок пика, потом основной отрезок). */
    val parts: List<TimeRange>,
    val layout: ClipLayout,
    val inAspect: Double,
    val frame: FramePlan,
    /** Субтитры во времени исходника. */
    val cues: List<SubtitleCue> = emptyList(),
    /** Текст-хук на первые 2 секунды (или null). */
    val hookText: String? = null,
    /** Во сколько раз изменить громкость (нормализация до −14 LUFS). */
    val audioGain: Float = 1f,
    val frameRate: Float = 30f,
)

/**
 * Делает вертикальный клип 1080×1920 (H.264 + AAC 192 кбит/с) через Media3 Transformer
 * с аппаратным кодированием — быстро, и телефон не греется.
 */
@UnstableApi
object ClipRenderer {
    private const val TAG = "ClipRenderer"
    const val OUT_W = 1080
    const val OUT_H = 1920

    /** Возвращает режим, которым реально сделан клип (если размытие не получилось — «Заполнить»). */
    suspend fun render(context: Context, input: File, output: File, plan: RenderPlan, onProgress: ((Int) -> Unit)? = null): ClipLayout {
        try {
            renderOnce(context, input, output, plan, onProgress)
            return plan.layout
        } catch (e: ExportException) {
            if (plan.layout == ClipLayout.FILL) throw e
            AppLog.w(TAG, "Режим ${plan.layout} не получился (${e.errorCodeName}) — делаю «Заполнить»", e)
            output.delete()
            renderOnce(context, input, output, plan.copy(layout = ClipLayout.FILL, frame = plan.frame.copy(cam = null, game = null)), onProgress)
            return ClipLayout.FILL
        }
    }

    private fun videoEffects(plan: RenderPlan, part: TimeRange, outOffset: Double): List<Effect> {
        val list = ArrayList<Effect>()
        when (plan.layout) {
            ClipLayout.BLUR -> list += ShortsBlurEffect.both(plan.inAspect.toFloat(), OUT_W, OUT_H)
            else -> list += LayoutEffect(plan.frame, part.startSec, OUT_W, OUT_H)
        }
        val overlays = ArrayList<androidx.media3.effect.TextureOverlay>()
        if (plan.cues.isNotEmpty()) overlays += TextOverlay.subtitles(plan.cues, part.startSec, outOffset)
        plan.hookText?.let { overlays += TextOverlay.hook(it, part.startSec, outOffset) }
        if (overlays.isNotEmpty()) list += OverlayEffect(overlays)
        return list
    }

    private fun audioProcessors(gain: Float): List<AudioProcessor> {
        if (gain in 0.98f..1.02f) return emptyList()
        val mixer = ChannelMixingAudioProcessor()
        for (n in listOf(1, 2, 6)) {
            val identity = FloatArray(n * n) { i -> if (i / n == i % n) gain else 0f }
            mixer.putChannelMixingMatrix(ChannelMixingMatrix(n, n, identity))
        }
        return listOf(mixer)
    }

    private suspend fun renderOnce(context: Context, input: File, output: File, plan: RenderPlan, onProgress: ((Int) -> Unit)?) {
        output.parentFile?.mkdirs()
        output.delete()
        var offset = 0.0
        val items = plan.parts.map { part ->
            val media = MediaItem.Builder()
                .setUri(Uri.fromFile(input))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs((part.startSec * 1000).toLong().coerceAtLeast(0))
                        .setEndPositionMs((part.endSec * 1000).toLong())
                        .build(),
                )
                .build()
            val item = EditedMediaItem.Builder(media)
                .setEffects(Effects(audioProcessors(plan.audioGain), videoEffects(plan, part, offset)))
                .build()
            offset += part.durationSec
            item
        }
        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
        val videoBitrate = if (plan.frameRate >= 50f) 14_000_000 else 9_000_000

        val main = Handler(Looper.getMainLooper())
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(192_000).build())
                    .setEnableFallback(true)
                    .build()
                val transformer = Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(encoderFactory)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer.start(composition, output.absolutePath)
                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress?.invoke(holder.progress)
                        main.postDelayed(this, 1_000)
                    }
                }
                main.postDelayed(poll, 1_000)
                cont.invokeOnCancellation {
                    main.post {
                        main.removeCallbacks(poll)
                        runCatching { transformer.cancel() }
                    }
                }
            }
        }
    }
}
