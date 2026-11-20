package app.clipradar.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.clipradar.util.AppLog
import app.clipradar.core.LoudnessMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Считает громкость звука по секундам: распаковывает звуковую дорожку
 * встроенным декодером телефона и меряет «силу» сигнала каждую секунду.
 */
object AudioAnalyzer {
    private const val TAG = "AudioAnalyzer"

    suspend fun loudnessPerSecond(file: File): List<Double> = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext emptyList()
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            var meter = LoudnessMeter(sampleRate, channels)
            var isFloat = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var shorts = ShortArray(0)
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val of = c.outputFormat
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        // Формат поменялся до первых данных — начинаем счёт заново.
                        meter = LoudnessMeter(sampleRate, channels)
                    }
                    outIdx >= 0 -> {
                        val out = c.getOutputBuffer(outIdx)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val ordered = out.order(ByteOrder.nativeOrder())
                            if (isFloat) {
                                val fb = ordered.asFloatBuffer()
                                while (fb.hasRemaining()) meter.feed(fb.get())
                            } else {
                                val sb = ordered.asShortBuffer()
                                val n = sb.remaining()
                                if (shorts.size < n) shorts = ShortArray(n)
                                sb.get(shorts, 0, n)
                                meter.feedPcm16(shorts, n)
                            }
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            meter.finish()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.w(TAG, "Не удалось проанализировать звук", e)
            emptyList()
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
