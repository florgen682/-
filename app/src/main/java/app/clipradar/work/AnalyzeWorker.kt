package app.clipradar.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.clipradar.core.InterestScorer
import app.clipradar.core.Loudness
import app.clipradar.core.MomentSignals
import app.clipradar.core.PeakFinder
import app.clipradar.core.Point
import app.clipradar.core.Sources
import app.clipradar.data.AppDatabase
import app.clipradar.data.MomentEntity
import app.clipradar.data.MomentStatus
import app.clipradar.data.Settings
import app.clipradar.live.WatchService
import app.clipradar.media.AudioAnalyzer
import app.clipradar.media.Gallery
import app.clipradar.media.UserFacingException
import app.clipradar.media.YtDlp
import app.clipradar.notify.Notifier
import kotlinx.coroutines.CancellationException

/**
 * «Найти моменты» для готового видео:
 *  1) если у YouTube есть график «самые пересматриваемые» — берём 5 лучших пиков;
 *  2) иначе качаем только звук и ищем 5 самых громких мест.
 */
class AnalyzeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val settings = Settings.get(ctx).current
        runCatching { setForeground(foregroundInfo("Получаю данные о видео…")) }
        stage("Получаю данные о видео…")
        val work = Gallery.workDir(ctx, "analyze_$id")
        try {
            val meta = YtDlp.info(ctx, url)

            if (meta.isLive) {
                val nick = Sources.twitchChannel(url)
                    ?: Sources.twitchChannel(meta.webpageUrl)
                    ?: if (meta.extractor.contains("twitch", true)) meta.uploader?.lowercase() else null
                if (nick != null) {
                    WatchService.watch(ctx, nick)
                    return Result.success(workDataOf(KEY_RESULT to "Это прямой эфир — включил наблюдение за $nick"))
                }
                return fail("Прямые эфиры пока поддерживаются только на Twitch")
            }

            val duration = meta.durationSec
            data class Found(val peak: Point, val signals: MomentSignals)

            val found: List<Found> = if (meta.heatmap.isNotEmpty()) {
                stage("Нашёл график пересмотров — выбираю пики")
                val points = PeakFinder.heatmapToPoints(meta.heatmap)
                val max = points.maxOf { it.value }
                val median = PeakFinder.median(points.map { it.value })
                val ignore = if ((duration ?: 0.0) > 180) 10.0 else 0.0
                PeakFinder.findTopPeaks(points, 5, 60.0, ignoreBeforeSec = ignore).map {
                    Found(it, MomentSignals(heatmap = InterestScorer.heatmapStrength(it.value, max, median)))
                }
            } else {
                stage("Графика нет — скачиваю только звук…")
                val audio = YtDlp.downloadAudio(ctx, url, work)
                stage("Ищу самые громкие места…")
                val db = AudioAnalyzer.loudnessPerSecond(audio)
                audio.delete()
                if (db.isEmpty()) throw UserFacingException("Не удалось разобрать звук видео")
                Loudness.loudestPeaks(db, 5, 60.0).map {
                    Found(it, MomentSignals(audioJumpDb = Loudness.jumpDb(db, it.timeSec)))
                }
            }
            if (found.isEmpty()) return fail("Не нашёл интересных моментов")

            val dao = AppDatabase.get(ctx).moments()
            var queued = 0
            for ((i, f) in found.withIndex()) {
                val score = InterestScorer.score(f.signals)
                val range = PeakFinder.rangeAround(
                    f.peak.timeSec, settings.vodBeforeSec.toDouble(), settings.vodAfterSec.toDouble(), duration,
                )
                val good = score.score >= settings.scoreThreshold
                val status = when {
                    !good -> MomentStatus.WEAK
                    settings.autoCut -> MomentStatus.QUEUED
                    else -> MomentStatus.FOUND
                }
                val id = dao.insert(
                    MomentEntity(
                        sourceUrl = meta.webpageUrl.ifBlank { url },
                        title = meta.title,
                        channel = meta.uploader,
                        isLive = false,
                        positionSec = f.peak.timeSec,
                        startSec = range.startSec,
                        endSec = range.endSec,
                        score = score.score,
                        reason = "#${i + 1}: ${score.reason}",
                        status = status,
                    ),
                )
                if (status == MomentStatus.QUEUED) {
                    ClipQueue.enqueue(ctx, id)
                    queued++
                }
            }
            val text = "Найдено ${found.size} моментов" + if (queued > 0) ", нарезаю $queued" else ""
            Notifier.info(ctx, "🔎 ${meta.title.take(60)}", text)
            return Result.success(workDataOf(KEY_RESULT to text))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("AnalyzeWorker", "Ошибка анализа", e)
            return fail(if (e is UserFacingException) e.message ?: "Ошибка" else YtDlp.explain(e))
        } finally {
            work.deleteRecursively()
        }
    }

    private fun fail(message: String): Result {
        Notifier.info(applicationContext, "Не получилось найти моменты", message)
        // Возвращаем success, чтобы следующие ссылки в очереди всё равно обработались.
        return Result.success(workDataOf(KEY_ERROR to message))
    }

    private suspend fun stage(text: String) {
        setProgress(workDataOf(KEY_STAGE to text))
        runCatching { setForeground(foregroundInfo(text)) }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val n = Notifier.progress(applicationContext, "🔎 Ищу лучшие моменты", text)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.ID_ANALYZE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.ID_ANALYZE, n)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Clip Radar")

    companion object {
        const val UNIQUE = "analyze"
        const val KEY_URL = "url"
        const val KEY_STAGE = "stage"
        const val KEY_ERROR = "error"
        const val KEY_RESULT = "result"

        fun start(context: Context, url: String) {
            val req = OneTimeWorkRequestBuilder<AnalyzeWorker>()
                .setInputData(workDataOf(KEY_URL to url))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(UNIQUE)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
