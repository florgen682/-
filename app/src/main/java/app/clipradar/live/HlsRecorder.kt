package app.clipradar.live

import app.clipradar.util.AppLog
import app.clipradar.core.HlsParser
import app.clipradar.core.NewSegment
import app.clipradar.core.SegmentRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Почему запись закончилась. */
enum class RecordEnd { STREAM_ENDED, NOT_LIVE, FAILED }

/**
 * Непрерывно скачивает кусочки прямого эфира (HLS) в папку [dir]
 * и держит в [ring] только последние несколько минут — старые файлы удаляются.
 *
 * @param resolveUrl получает свежую ссылку на поток через yt-dlp (ссылки Twitch иногда «протухают»).
 */
class HlsRecorder(
    private val dir: File,
    private val ring: SegmentRing,
    private val maxHeight: Int,
    private val resolveUrl: suspend () -> String,
) {
    @Volatile var segmentsDownloaded = 0
        private set

    /** «Шапка» fMP4-потока, если она есть (её нужно ставить в начало склейки). */
    @Volatile var initFile: File? = null
        private set

    suspend fun run(): RecordEnd {
        dir.mkdirs()
        var url = try {
            resolveUrl()
        } catch (e: Exception) {
            AppLog.w(TAG, "Не получил ссылку на поток", e)
            return RecordEnd.NOT_LIVE
        }
        var lastSeq = -1L
        var failures = 0
        while (coroutineContext.isActive) {
            val text = try {
                fetchText(url)
            } catch (e: IOException) {
                // Эфир оборвался или пропал интернет: переподключаемся до 10 раз с паузой.
                // Буфер при этом не трогаем — уже найденные моменты доделаются.
                failures++
                AppLog.w(TAG, "Плейлист не загрузился (попытка $failures из $MAX_RECONNECTS): ${e.message}")
                if (failures > MAX_RECONNECTS) return RecordEnd.FAILED
                delay((3_000L * failures).coerceAtMost(20_000))
                if (failures >= 2) {
                    url = try {
                        resolveUrl()
                    } catch (e2: Exception) {
                        val msg = e2.message?.lowercase() ?: ""
                        if ("не в эфире" in msg || "not currently live" in msg || "offline" in msg) return RecordEnd.NOT_LIVE
                        AppLog.w(TAG, "Новая ссылка на поток не получена: ${e2.message}")
                        url
                    }
                }
                continue
            }
            if (failures > 0) AppLog.i(TAG, "Связь с эфиром восстановлена после $failures попыток")
            failures = 0
            val playlist = HlsParser.parse(text, url)
            if (playlist.isMaster) {
                url = HlsParser.pickVariant(playlist.variants, maxHeight)?.uri ?: return RecordEnd.FAILED
                continue
            }
            if (playlist.initUri != null && initFile == null) {
                initFile = runCatching { download(playlist.initUri!!, File(dir, "init.mp4")) }.getOrNull()
            }

            val fresh = playlist.segments.filter { it.sequence > lastSeq }
            val batch = ArrayList<NewSegment>()
            for (seg in fresh) {
                if (seg.isTwitchAd) continue // рекламу в клипы не берём
                val file = File(dir, "seg_${seg.sequence}.ts")
                try {
                    download(seg.uri, file)
                    batch += NewSegment(seg.sequence, file.absolutePath, (seg.durationSec * 1000).toLong())
                    segmentsDownloaded++
                } catch (e: IOException) {
                    AppLog.w(TAG, "Кусок ${seg.sequence} не скачался: ${e.message}")
                    file.delete()
                }
            }
            if (fresh.isNotEmpty()) lastSeq = fresh.maxOf { it.sequence }
            val now = System.currentTimeMillis()
            if (batch.isNotEmpty()) ring.addBatch(batch, now)
            for (old in ring.prune(now)) File(old.file).delete()

            if (playlist.endList) return RecordEnd.STREAM_ENDED
            delay((playlist.targetDurationSec * 500).toLong().coerceIn(1_000, 4_000))
        }
        return RecordEnd.FAILED
    }

    private suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("пустой ответ")
        }
    }

    private suspend fun download(url: String, target: File): File = withContext(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val tmp = File(target.parentFile, target.name + ".part")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) throw IOException("не удалось сохранить кусок")
        }
        target
    }

    companion object {
        private const val TAG = "HlsRecorder"
        private const val MAX_RECONNECTS = 10
    }
}
