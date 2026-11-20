package app.clipradar.media

import android.content.Context
import android.util.Log
import app.clipradar.core.HeatmapSegment
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Сведения о видео или эфире, которые вернул yt-dlp. */
data class VideoMeta(
    val id: String,
    val title: String,
    val durationSec: Double?,
    val isLive: Boolean,
    val extractor: String,
    val uploader: String?,
    val heatmap: List<HeatmapSegment>,
    val webpageUrl: String,
)

/** Понятная ошибка, которую можно показать пользователю. */
class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Всё общение с yt-dlp (он работает внутри приложения через Python). */
object YtDlp {
    private const val TAG = "YtDlp"
    private val initMutex = Mutex()
    @Volatile private var ready = false

    suspend fun ensureInit(context: Context) {
        if (ready) return
        initMutex.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
            }
            ready = true
        }
    }

    /** Запуск yt-dlp; если корутину отменили — процесс тоже останавливается. */
    private suspend fun run(context: Context, request: YoutubeDLRequest): YoutubeDLResponse {
        ensureInit(context)
        val pid = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            thread(name = "yt-dlp-$pid") {
                try {
                    val r = YoutubeDL.getInstance().execute(request, pid, false, null)
                    if (cont.isActive) cont.resume(r)
                } catch (e: Throwable) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
            cont.invokeOnCancellation { runCatching { YoutubeDL.getInstance().destroyProcessById(pid) } }
        }
    }

    suspend fun info(context: Context, url: String): VideoMeta {
        val req = YoutubeDLRequest(url)
            .addOption("--dump-single-json")
            .addOption("--no-playlist")
            .addOption("--skip-download")
        val out = try {
            run(context, req).out
        } catch (e: Exception) {
            throw UserFacingException(explain(e), e)
        }
        val start = out.indexOf('{')
        val end = out.lastIndexOf('}')
        if (start < 0 || end <= start) throw UserFacingException("yt-dlp не вернул данные о видео")
        val json = JSONObject(out.substring(start, end + 1))
        val heat = ArrayList<HeatmapSegment>()
        json.optJSONArray("heatmap")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                heat += HeatmapSegment(o.optDouble("start_time"), o.optDouble("end_time"), o.optDouble("value"))
            }
        }
        val liveStatus = json.optString("live_status")
        return VideoMeta(
            id = json.optString("id"),
            title = json.optString("title").ifBlank { url },
            durationSec = json.optDouble("duration").takeIf { !it.isNaN() && it > 0 },
            isLive = json.optBoolean("is_live", false) || liveStatus == "is_live",
            extractor = json.optString("extractor_key", json.optString("extractor")),
            uploader = json.optString("uploader").takeIf { it.isNotBlank() },
            heatmap = heat.filter { !it.value.isNaN() },
            webpageUrl = json.optString("webpage_url", url),
        )
    }

    /** Скачивает ТОЛЬКО нужный кусок видео (--download-sections), до [maxHeight]p. */
    suspend fun downloadSection(
        context: Context,
        url: String,
        startSec: Double,
        endSec: Double,
        maxHeight: Int,
        dir: File,
    ): File {
        dir.mkdirs()
        val h = maxHeight
        val format = "bv*[height<=$h][vcodec^=avc1]+ba[ext=m4a]/bv*[height<=$h][vcodec^=avc]+ba/" +
            "b[height<=$h][ext=mp4]/bv*[height<=$h]+ba/b[height<=$h]/b"
        val req = YoutubeDLRequest(url)
            .addOption("-f", format)
            .addOption("--download-sections", "*${sec(startSec)}-${sec(endSec)}")
            .addOption("--merge-output-format", "mp4")
            .addOption("--no-playlist")
            .addOption("--no-part")
            .addOption("--no-mtime")
            .addOption("-o", File(dir, "section.%(ext)s").absolutePath)
        try {
            run(context, req)
        } catch (e: Exception) {
            throw UserFacingException(explain(e), e)
        }
        return newestMedia(dir) ?: throw UserFacingException("Кусок видео не скачался")
    }

    /** Скачивает только звук (в самом лёгком качестве) — чтобы найти громкие места. */
    suspend fun downloadAudio(context: Context, url: String, dir: File): File {
        dir.mkdirs()
        val req = YoutubeDLRequest(url)
            .addOption("-f", "wa[ext=m4a]/ba[ext=m4a]/wa/ba/w")
            .addOption("--no-playlist")
            .addOption("--no-part")
            .addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
        try {
            run(context, req)
        } catch (e: Exception) {
            throw UserFacingException(explain(e), e)
        }
        return newestMedia(dir) ?: throw UserFacingException("Звук не скачался")
    }

    /** Ссылка на HLS-поток (m3u8) прямого эфира. */
    suspend fun liveStreamUrl(context: Context, url: String, maxHeight: Int): String {
        val req = YoutubeDLRequest(url)
            .addOption("-g")
            .addOption("-f", "best[height<=$maxHeight]/best")
        val out = try {
            run(context, req).out
        } catch (e: Exception) {
            throw UserFacingException(explain(e), e)
        }
        return out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http") }
            ?: throw UserFacingException("Не удалось получить ссылку на эфир")
    }

    /** Обновляет yt-dlp до свежей версии (сайты часто меняются). */
    suspend fun update(context: Context): String {
        ensureInit(context)
        return withContext(Dispatchers.IO) {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            val version = YoutubeDL.getInstance().versionName(context.applicationContext) ?: "?"
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "yt-dlp обновлён: $version"
                else -> "yt-dlp уже свежий: $version"
            }
        }
    }

    fun version(context: Context): String =
        runCatching { YoutubeDL.getInstance().versionName(context.applicationContext) }.getOrNull() ?: "—"

    private fun newestMedia(dir: File): File? =
        dir.listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.extension.lowercase(Locale.ROOT) in setOf("mp4", "m4a", "mkv", "webm", "ts", "mov", "opus", "aac", "mp3") }
            ?.maxByOrNull { it.lastModified() }

    private fun sec(v: Double) = String.format(Locale.US, "%.2f", maxOf(0.0, v))

    /** Переводит техническую ошибку yt-dlp в понятный текст. */
    fun explain(e: Throwable): String {
        val msg = (e.message ?: e.toString())
        Log.w(TAG, "yt-dlp error: $msg")
        val lower = msg.lowercase(Locale.ROOT)
        return when {
            "is not currently live" in lower || "offline" in lower -> "Канал сейчас не в эфире"
            "private video" in lower -> "Это приватное видео"
            "sign in to confirm" in lower -> "YouTube просит подтвердить, что вы не робот. Попробуйте позже или обновите yt-dlp в настройках"
            "unsupported url" in lower -> "Эта ссылка не поддерживается"
            "unable to download" in lower || "timed out" in lower || "network" in lower -> "Проблема с интернетом: ${msg.take(160)}"
            "video unavailable" in lower -> "Видео недоступно"
            else -> "Ошибка yt-dlp: ${msg.lineSequence().lastOrNull { it.isNotBlank() }?.take(200) ?: msg.take(200)}"
        }
    }
}
