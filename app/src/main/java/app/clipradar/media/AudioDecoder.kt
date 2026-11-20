package app.clipradar.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import app.clipradar.core.LoudnessMeter
import app.clipradar.core.LufsMeter
import app.clipradar.core.MonoResampler
import app.clipradar.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/** Результат одного прохода по звуку исходника. */
class AudioScan(
    /** Звук 16 кГц моно для Whisper (null, если не просили). */
    val pcm16k: FloatArray?,
    /** Громкость каждые 0.5 с, дБ. */
    val loudHalfSec: List<Double>,
    /** Громкость каждую секунду, дБ. */
    val loudPerSec: List<Double>,
    /** Измеритель LUFS по всему исходнику (умеет считать громкость любого отрезка). */
    val lufs: LufsMeter?,
    val durationSec: Double,
)

/** Декодирует звуковую дорожку один раз и сразу считает всё нужное. */
object AudioDecoder {
    private const val TAG = "AudioDecoder"

    suspend fun scan(file: File, want16k: Boolean): AudioScan = withContext(Dispatchers.Default) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext AudioScan(null, emptyList(), emptyList(), null, 0.0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()

            var half = LoudnessMeter(maxOf(1, sampleRate / 2), channels)
            var sec = LoudnessMeter(sampleRate, channels)
            var lufs = LufsMeter(sampleRate, channels)
            var resampler = if (want16k) MonoResampler(sampleRate, channels) else null
            var isFloat = false
            var started = false
            var lastPtsUs = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var shorts = ShortArray(0)

            fun push(v: Float) {
                half.feed(v)
                sec.feed(v)
                lufs.feed(v)
                resampler?.feed(v)
            }

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val size = extractor.readSampleData(c.getInputBuffer(inIdx)!!, 0)
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
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    if (!started) {
                        half = LoudnessMeter(maxOf(1, sampleRate / 2), channels)
                        sec = LoudnessMeter(sampleRate, channels)
                        lufs = LufsMeter(sampleRate, channels)
                        resampler = if (want16k) MonoResampler(sampleRate, channels) else null
                    }
                } else if (outIdx >= 0) {
                    val out = c.getOutputBuffer(outIdx)
                    if (out != null && info.size > 0) {
                        started = true
                        lastPtsUs = info.presentationTimeUs
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        val ordered = out.order(ByteOrder.nativeOrder())
                        if (isFloat) {
                            val fb = ordered.asFloatBuffer()
                            while (fb.hasRemaining()) push(fb.get())
                        } else {
                            val sb = ordered.asShortBuffer()
                            val n = sb.remaining()
                            if (shorts.size < n) shorts = ShortArray(n)
                            sb.get(shorts, 0, n)
                            for (i in 0 until n) push(shorts[i] / 32768f)
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            val halfList = half.finish()
            AudioScan(resampler?.result(), halfList, sec.finish(), lufs, maxOf(halfList.size * 0.5, lastPtsUs / 1e6))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            AppLog.w(TAG, "Не удалось разобрать звук ${file.name}", e)
            AudioScan(null, emptyList(), emptyList(), null, 0.0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
