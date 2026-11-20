package app.clipradar.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.clipradar.data.AppDatabase
import app.clipradar.data.MomentEntity
import app.clipradar.data.MomentStatus
import app.clipradar.data.Settings
import app.clipradar.media.ClipRenderer
import app.clipradar.media.Gallery
import app.clipradar.media.UserFacingException
import app.clipradar.media.YtDlp
import app.clipradar.notify.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.clipradar.core.TimeFormat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Очередь нарезки: клипы режутся строго по одному. */
object ClipQueue {
    private const val QUEUE = "clip-queue"

    suspend fun enqueue(context: Context, momentId: Long) {
        val m = AppDatabase.get(context).moments().get(momentId) ?: return
        AppDatabase.get(context).moments().setStatus(momentId, MomentStatus.QUEUED, null)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (m.isLive) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED)
            .build()
        val req = OneTimeWorkRequestBuilder<ClipWorker>()
            .setInputData(workDataOf(ClipWorker.KEY_MOMENT to momentId))
            .setConstraints(constraints)
            .addTag("clip")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

/** Нарезает один клип: скачивает кусок (или берёт из буфера эфира), делает 9:16 и кладёт в галерею. */
@UnstableApi
class ClipWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val dao = AppDatabase.get(ctx).moments()
        val id = inputData.getLong(KEY_MOMENT, -1)
        val m = dao.get(id) ?: return Result.success() // момент уже удалили
        if (m.status != MomentStatus.QUEUED && m.status != MomentStatus.CUTTING) return Result.success()

        if (!Gallery.hasEnoughSpace(ctx)) {
            dao.setStatus(id, MomentStatus.FAILED, "Мало места: свободно меньше 1 ГБ")
            Notifier.lowStorage(ctx)
            return Result.success()
        }

        runCatching { setForeground(foregroundInfo(m.title, "Готовлюсь…")) }
        dao.setStatus(id, MomentStatus.CUTTING, null)
        val settings = Settings.get(ctx).current
        val work = Gallery.workDir(ctx, "clip_$id")
        val started = System.currentTimeMillis()
        var downloadMs = 0L
        try {
            stage(id, m.title, if (m.isLive) "Беру кусок из буфера эфира…" else "Скачиваю кусок видео…")
            val input: File = if (m.isLive) {
                val f = m.sourceFile?.let(::File)
                if (f == null || !f.exists()) throw UserFacingException("Кусок эфира уже удалён — нарезать заново нельзя")
                f
            } else {
                withTimeoutOrNull(STAGE_TIMEOUT_MS) {
                    YtDlp.downloadSection(ctx, m.sourceUrl, m.startSec, m.endSec, settings.quality, work) { p ->
                        stageAsync(id, m.title, "Скачиваю кусок видео… " + (p?.let { "$it%" } ?: ""))
                    }
                } ?: throw UserFacingException(
                    "Кусок видео качался дольше 10 минут — похоже, медленный интернет. Попробуйте «Нарезать заново» на Wi-Fi",
                )
            }
            downloadMs = System.currentTimeMillis() - started

            stage(id, m.title, "Делаю вертикальный клип 9:16…")
            val out = File(work, "clip.mp4")
            withTimeoutOrNull(STAGE_TIMEOUT_MS) {
                ClipRenderer.render(ctx, input, out, settings.layout) { p ->
                    stageAsync(id, m.title, "Делаю вертикальный клип 9:16… $p%")
                }
            } ?: throw UserFacingException(
                "Обработка видео заняла больше 10 минут. Попробуйте в настройках «Чёрные поля» или качество 480p",
            )
            val renderMs = System.currentTimeMillis() - started - downloadMs

            val current = dao.get(id)
            if (current == null) return Result.success() // пока резали — удалили

            stage(id, m.title, "Сохраняю в галерею…")
            current.clipUri?.let { Gallery.delete(ctx, Uri.parse(it)) } // старая версия клипа
            val uri = Gallery.saveToGallery(ctx, out, fileName(current))
            dao.update(
                current.copy(status = MomentStatus.READY, clipUri = uri.toString(), clipSize = out.length(), error = null),
            )
            val timing = "Готов за ${sec(downloadMs + renderMs)}" +
                (if (!m.isLive) " (скачивание ${sec(downloadMs)} · обработка ${sec(renderMs)})" else " (обработка ${sec(renderMs)})")
            Log.i(TAG, "Клип $id: $timing")
            Notifier.clipReady(ctx, id, "${current.title}\n$timing", uri)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.setStatus(id, MomentStatus.FOUND, "Нарезка прервана") }
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Нарезка не удалась", e)
            val msg = when (e) {
                is UserFacingException -> e.message
                is androidx.media3.transformer.ExportException -> "Ошибка кодирования видео (${e.errorCodeName})"
                else -> e.message ?: e.toString()
            }
            dao.setStatus(id, MomentStatus.FAILED, msg)
        } finally {
            ClipProgress.clear(id)
            // Временные файлы всегда удаляем.
            work.deleteRecursively()
        }
        return Result.success()
    }

    private var lastNotify = 0L

    /** Показать этап на экране и в уведомлении. */
    private suspend fun stage(id: Long, title: String, text: String) {
        ClipProgress.set(id, text)
        lastNotify = System.currentTimeMillis()
        runCatching { setForeground(foregroundInfo(title, text)) }
    }

    /** То же, но из «чужого» потока (проценты приходят часто — уведомление обновляем раз в 2 секунды). */
    private fun stageAsync(id: Long, title: String, text: String) {
        ClipProgress.set(id, text)
        val now = System.currentTimeMillis()
        if (now - lastNotify < 2_000) return
        lastNotify = now
        runCatching { setForegroundAsync(foregroundInfo(title, text)) }
    }

    private fun sec(ms: Long): String = TimeFormat.duration(ms / 1000.0)

    private fun foregroundInfo(title: String, text: String): ForegroundInfo {
        val n = Notifier.progress(applicationContext, "✂️ $text", title)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.ID_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.ID_WORK, n)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Clip Radar", "Нарезаю клип")

    private fun fileName(m: MomentEntity): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = (m.channel ?: m.title).replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(40).trim('_')
        return "ClipRadar_${name}_${stamp}_${m.id}.mp4"
    }

    companion object {
        const val KEY_MOMENT = "momentId"
        private const val STAGE_TIMEOUT_MS = 10 * 60_000L
        private const val TAG = "ClipWorker"
    }
}
