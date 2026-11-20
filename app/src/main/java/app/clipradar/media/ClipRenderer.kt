package app.clipradar.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import app.clipradar.data.ClipLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Делает из обычного видео вертикальный клип 1080x1920 (H.264 + AAC) через Media3 Transformer.
 * Transformer использует аппаратный кодировщик телефона — это быстро и телефон не греется.
 */
@UnstableApi
object ClipRenderer {
    private const val TAG = "ClipRenderer"
    const val OUT_W = 1080
    const val OUT_H = 1920

    /**
     * @param clipStartMs/clipEndMs — если заданы, вырезаем только этот отрезок исходника.
     * Если размытый фон не получился (бывает на старых телефонах) — делаем чёрные поля.
     */
    suspend fun render(
        context: Context,
        input: File,
        output: File,
        layout: ClipLayout,
        clipStartMs: Long? = null,
        clipEndMs: Long? = null,
        /** Процент готовности 0..100. */
        onProgress: ((Int) -> Unit)? = null,
    ): ClipLayout {
        try {
            renderOnce(context, input, output, layout, clipStartMs, clipEndMs, onProgress)
            return layout
        } catch (e: ExportException) {
            if (layout != ClipLayout.BLUR) throw e
            Log.w(TAG, "Размытый фон не получился (${e.errorCodeName}), делаю чёрные поля", e)
            output.delete()
            renderOnce(context, input, output, ClipLayout.BLACK, clipStartMs, clipEndMs, onProgress)
            return ClipLayout.BLACK
        }
    }

    private fun sourceAspect(input: File): Float {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(input.absolutePath)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 16f
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 9f
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h / w else w / h
        } catch (e: Exception) {
            16f / 9f
        } finally {
            runCatching { r.release() }
        }
    }

    private fun effectsFor(layout: ClipLayout, aspect: Float): List<Effect> = when (layout) {
        ClipLayout.BLUR -> ShortsBlurEffect.both(aspect, OUT_W, OUT_H)
        ClipLayout.BLACK -> listOf(Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_SCALE_TO_FIT))
        ClipLayout.CROP -> listOf(Presentation.createForWidthAndHeight(OUT_W, OUT_H, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
    }

    private suspend fun renderOnce(
        context: Context,
        input: File,
        output: File,
        layout: ClipLayout,
        clipStartMs: Long?,
        clipEndMs: Long?,
        onProgress: ((Int) -> Unit)?,
    ) {
        output.parentFile?.mkdirs()
        output.delete()
        val aspect = withContext(Dispatchers.IO) { sourceAspect(input) }

        val itemBuilder = MediaItem.Builder().setUri(Uri.fromFile(input))
        if (clipStartMs != null || clipEndMs != null) {
            val clip = MediaItem.ClippingConfiguration.Builder()
            clipStartMs?.let { clip.setStartPositionMs(maxOf(0L, it)) }
            clipEndMs?.let { clip.setEndPositionMs(it) }
            itemBuilder.setClippingConfiguration(clip.build())
        }
        val edited = EditedMediaItem.Builder(itemBuilder.build())
            .setEffects(Effects(emptyList(), effectsFor(layout, aspect)))
            .build()

        val main = Handler(Looper.getMainLooper())
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
                    .setRequestedVideoEncoderSettings(
                        VideoEncoderSettings.Builder().setBitrate(8_000_000).build(),
                    )
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

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            if (cont.isActive) cont.resumeWithException(exportException)
                        }
                    })
                    .build()
                transformer.start(edited, output.absolutePath)
                // Раз в секунду спрашиваем у Transformer, сколько процентов готово.
                val holder = ProgressHolder()
                val poll = object : Runnable {
                    override fun run() {
                        if (!cont.isActive) return
                        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress?.invoke(holder.progress)
                        }
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
