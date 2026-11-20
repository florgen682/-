package app.clipradar.media

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import app.clipradar.core.FaceBox
import app.clipradar.core.FrameSample
import app.clipradar.core.NRect
import app.clipradar.util.AppLog
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Что удалось узнать о картинке исходника. */
class FrameAnalysis(
    /** Кадры с шагом ~0.5 с: лица и центр движения (время — секунды от начала файла). */
    val samples: List<FrameSample>,
    /** Средняя разница соседних кадров (0..255) для каждого сэмпла — для поиска смен сцен и «стоп-кадров». */
    val diffs: List<Pair<Double, Double>>,
    /** Несколько кадров в яркости 160×90 — для поиска рамки вебки. */
    val lumaFrames: List<IntArray>,
    val width: Int,
    val height: Int,
    val frameRate: Float,
) {
    /** Резкие смены картинки (секунды). */
    fun sceneCuts(): List<Double> {
        if (diffs.size < 3) return emptyList()
        val med = app.clipradar.core.PeakFinder.median(diffs.map { it.second })
        return diffs.filter { it.second > 28.0 && it.second > med * 3.0 }.map { it.first }
    }

    /** Разница кадров по секундам (для проверки «стоп-кадра»). */
    fun diffPerSecond(fromSec: Double, toSec: Double): List<Double?> {
        val n = (toSec - fromSec).toInt().coerceAtLeast(0)
        return (0 until n).map { i ->
            val s = fromSec + i
            diffs.filter { it.first >= s && it.first < s + 1 }.map { it.second }.takeIf { it.isNotEmpty() }?.average()
        }
    }

    val aspect: Double get() = if (height > 0) width.toDouble() / height else 16.0 / 9
}

/**
 * Один последовательный проход декодером по видео: каждые 0.5 с берём уменьшенную яркость кадра
 * (смена сцены, движение), каждую секунду ищем лица через ML Kit.
 */
object FrameAnalyzer {
    private const val TAG = "FrameAnalyzer"
    private const val TW = 64
    private const val TH = 36

    suspend fun analyze(file: File, fromSec: Double, toSec: Double, detectFaces: Boolean): FrameAnalysis =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            val detector = if (detectFaces) {
                FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setMinFaceSize(0.04f)
                        .build(),
                )
            } else {
                null
            }
            val samples = ArrayList<FrameSample>()
            val diffs = ArrayList<Pair<Double, Double>>()
            val bigFrames = ArrayList<IntArray>()
            var width = 0
            var height = 0
            var fps = 30f
            try {
                extractor.setDataSource(file.absolutePath)
                val track = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: return@withContext FrameAnalysis(emptyList(), emptyList(), emptyList(), 0, 0, 30f)
                extractor.selectTrack(track)
                val format = extractor.getTrackFormat(track)
                width = format.getInteger(MediaFormat.KEY_WIDTH)
                height = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    fps = runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                        .getOrElse { format.getFloat(MediaFormat.KEY_FRAME_RATE) }
                }
                val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
                if (rotation == 90 || rotation == 270) {
                    val t = width; width = height; height = t
                }
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec = c
                c.configure(format, null, null, 0)
                c.start()
                extractor.seekTo((fromSec * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                val fromUs = (fromSec * 1e6).toLong()
                val toUs = (toSec * 1e6).toLong()
                val bigAt = listOf(0.25, 0.5, 0.75).map { fromUs + ((toUs - fromUs) * it).toLong() }.toMutableList()
                var nextSampleUs = fromUs
                var nextFaceUs = fromUs
                var prevThumb: IntArray? = null
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    coroutineContext.ensureActive()
                    if (!inputDone) {
                        val inIdx = c.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val size = extractor.readSampleData(c.getInputBuffer(inIdx)!!, 0)
                            if (size < 0 || extractor.sampleTime > toUs + 500_000) {
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                c.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIdx = c.dequeueOutputBuffer(info, 10_000)
                    if (outIdx >= 0) {
                        val pts = info.presentationTimeUs
                        if (pts >= nextSampleUs && pts <= toUs && info.size > 0) {
                            val image = runCatching { c.getOutputImage(outIdx) }.getOrNull()
                            if (image != null && image.format == ImageFormat.YUV_420_888) {
                                val t = pts / 1e6
                                val thumb = lumaThumb(image, TW, TH)
                                val prev = prevThumb
                                var motionX: Double? = null
                                if (prev != null) {
                                    diffs += t to meanDiff(prev, thumb)
                                    motionX = motionCenter(prev, thumb, TW, TH)
                                }
                                prevThumb = thumb
                                if (bigAt.isNotEmpty() && pts >= bigAt.first()) {
                                    bigAt.removeAt(0)
                                    bigFrames += lumaThumb(image, 160, 90)
                                }
                                var faces = emptyList<FaceBox>()
                                if (detector != null && pts >= nextFaceUs) {
                                    nextFaceUs = pts + 1_000_000
                                    faces = runCatching {
                                        val input = InputImage.fromMediaImage(image, rotation)
                                        val found = Tasks.await(detector.process(input), 5, TimeUnit.SECONDS)
                                        found.map { f ->
                                            val b = f.boundingBox
                                            FaceBox(
                                                t,
                                                NRect(
                                                    b.left.toDouble() / width, b.top.toDouble() / height,
                                                    b.width().toDouble() / width, b.height().toDouble() / height,
                                                ),
                                            )
                                        }
                                    }.getOrElse { emptyList() }
                                }
                                samples += FrameSample(t, faces, motionX)
                            }
                            image?.close()
                            nextSampleUs = pts + 500_000
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || pts > toUs) outputDone = true
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                AppLog.w(TAG, "Анализ кадров не удался (${file.name})", e)
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { detector?.close() }
                extractor.release()
            }
            FrameAnalysis(samples, diffs, bigFrames, width, height, fps)
        }

    /** Уменьшенная копия яркости кадра (w×h). */
    private fun lumaThumb(image: Image, w: Int, h: Int): IntArray {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride
        val ps = plane.pixelStride
        val iw = image.width
        val ih = image.height
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val sy = (y * ih / h).coerceIn(0, ih - 1)
            for (x in 0 until w) {
                val sx = (x * iw / w).coerceIn(0, iw - 1)
                val idx = sy * rs + sx * ps
                out[y * w + x] = if (idx < buf.limit()) buf.get(idx).toInt() and 0xFF else 0
            }
        }
        return out
    }

    private fun meanDiff(a: IntArray, b: IntArray): Double {
        var s = 0L
        for (i in a.indices) s += abs(a[i] - b[i])
        return s.toDouble() / a.size
    }

    /** Где по горизонтали больше всего движения (0..1) — «главное действие». */
    private fun motionCenter(a: IntArray, b: IntArray, w: Int, h: Int): Double? {
        val cols = DoubleArray(w)
        var total = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val d = abs(a[y * w + x] - b[y * w + x]).toDouble()
            if (d > 12) {
                cols[x] += d
                total += d
            }
        }
        if (total < w * h * 0.5) return null // почти ничего не движется
        var acc = 0.0
        for (x in 0 until w) acc += cols[x] * (x + 0.5) / w
        return acc / total
    }
}
