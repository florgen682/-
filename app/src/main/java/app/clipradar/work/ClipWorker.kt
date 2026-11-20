package app.clipradar.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
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
import app.clipradar.core.BoundaryPicker
import app.clipradar.core.BoundsConfig
import app.clipradar.core.CaptionWriter
import app.clipradar.core.ClipBounds
import app.clipradar.core.CropPlanner
import app.clipradar.core.FrameSample
import app.clipradar.core.LufsMeter
import app.clipradar.core.MomentContext
import app.clipradar.core.MomentMerger
import app.clipradar.core.NRect
import app.clipradar.core.PeakFinder
import app.clipradar.core.QualityCheck
import app.clipradar.core.Subtitles
import app.clipradar.core.TimeFormat
import app.clipradar.core.TimeRange
import app.clipradar.core.Word
import app.clipradar.data.AppDatabase
import app.clipradar.data.ClipLayout
import app.clipradar.data.MomentDetails
import app.clipradar.data.MomentEntity
import app.clipradar.data.MomentStatus
import app.clipradar.data.Settings
import app.clipradar.media.AudioDecoder
import app.clipradar.media.ClipRenderer
import app.clipradar.media.FrameAnalysis
import app.clipradar.media.FrameAnalyzer
import app.clipradar.media.FramePlan
import app.clipradar.media.Gallery
import app.clipradar.media.RenderPlan
import app.clipradar.media.UserFacingException
import app.clipradar.media.YtDlp
import app.clipradar.notify.Notifier
import app.clipradar.speech.Transcriber
import app.clipradar.speech.WhisperModel
import app.clipradar.util.AppLog
import app.clipradar.util.DeviceGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Очередь нарезки: клипы режутся строго по одному. */
object ClipQueue {
    private const val QUEUE = "clip-queue"

    /** [force] — резать, даже если проверка качества считает момент плохим (кнопка «Нарезать»). */
    suspend fun enqueue(context: Context, momentId: Long, force: Boolean = false) {
        val m = AppDatabase.get(context).moments().get(momentId) ?: return
        AppDatabase.get(context).moments().setStatus(momentId, MomentStatus.QUEUED, null)
        val needsNet = !m.isLive && (m.sourceFile == null || !File(m.sourceFile).exists())
        val req = OneTimeWorkRequestBuilder<ClipWorker>()
            .setInputData(workDataOf(ClipWorker.KEY_MOMENT to momentId, ClipWorker.KEY_FORCE to force))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (needsNet) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .addTag("clip")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

/**
 * Нарезает один клип:
 *  исходник → звук → речь (Whisper) → кадры (лица, сцены) → умные границы → проверка качества и дублей →
 *  кадрирование 9:16 → субтитры и подписи → громкость −14 LUFS → рендер → галерея.
 */
@UnstableApi
class ClipWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val dao = AppDatabase.get(ctx).moments()
        val id = inputData.getLong(KEY_MOMENT, -1)
        val force = inputData.getBoolean(KEY_FORCE, false)
        val m0 = dao.get(id) ?: return Result.success() // момент уже удалили
        if (m0.status != MomentStatus.QUEUED && m0.status != MomentStatus.CUTTING) return Result.success()

        if (!Gallery.hasEnoughSpace(ctx)) {
            dao.setStatus(id, MomentStatus.FAILED, "Мало места: свободно меньше 1 ГБ")
            Notifier.lowStorage(ctx)
            return Result.success()
        }
        runCatching { setForeground(foregroundInfo(m0.title, "Готовлюсь…")) }
        dao.setStatus(id, MomentStatus.CUTTING, null)
        val settings = Settings.get(ctx).current
        val work = Gallery.workDir(ctx, "clip_$id")
        val started = System.currentTimeMillis()
        try {
            guard(id, m0.title)

            // 1) Исходник (для готовых видео — кусок с запасом ±60 с, сохраняем для ручной правки).
            stage(id, m0.title, if (m0.isLive) "Беру кусок из буфера эфира…" else "Скачиваю кусок видео с запасом…")
            var m = ensureSource(m0, settings.quality, work)
            val source = File(m.sourceFile!!)
            val tDownload = System.currentTimeMillis()

            // 2) Звук: громкость, LUFS, 16 кГц для Whisper.
            var details = MomentDetails.parse(m.details)
            val needWords = settings.speechEnabled && details.words == null
            stage(id, m.title, "Слушаю звук…")
            val audio = AudioDecoder.scan(source, want16k = needWords)
            val duration = maxOf(audio.durationSec, m.clipEndSec, 1.0)
            val peak = (if (m.peakInSource >= 0) m.peakInSource else duration / 2).coerceIn(0.0, duration)

            // 3) Речь.
            var words: List<Word> = details.words ?: emptyList()
            if (needWords && audio.pcm16k != null) {
                guard(id, m.title)
                words = transcribe(id, m.title, audio.pcm16k, peak, settings)
                details = details.copy(words = words)
            }

            // 4) Картинка: лица, смены сцен, стоп-кадры.
            guard(id, m.title)
            stage(id, m.title, "Смотрю картинку: лица и смены сцен…")
            val frames = FrameAnalyzer.analyze(
                source, maxOf(0.0, peak - 60), minOf(duration, peak + 60), detectFaces = settings.layout != ClipLayout.BLUR,
            )
            val sceneCuts = frames.sceneCuts()

            // 5) Границы клипа.
            val manual = m.manualStartSec != null && m.manualEndSec != null
            val bounds: ClipBounds = if (manual) {
                ClipBounds(m.manualStartSec!!, m.manualEndSec!!, peak, "выставлено вручную", "выставлено вручную", null)
            } else {
                BoundaryPicker.pick(
                    MomentContext(
                        durationSec = duration,
                        peakSec = peak,
                        words = words,
                        loudnessDb = audio.loudHalfSec,
                        loudnessStepSec = 0.5,
                        sceneCuts = sceneCuts,
                        chatPerSec = details.chatPerSec ?: emptyList(),
                        chatBaseline = details.chatBaseline,
                        mustInclude = details.mustInclude,
                    ),
                    BoundsConfig(maxLenSec = settings.maxClipSec.toDouble().coerceIn(12.0, 180.0)),
                )
            }
            AppLog.i(
                TAG,
                "Клип $id: ${fmt(bounds.start)}–${fmt(bounds.end)} (${fmt(bounds.length)} с). Начало: ${bounds.startReason}. Конец: ${bounds.endReason}",
            )
            details = details.copy(startReason = bounds.startReason, endReason = bounds.endReason, hook = bounds.hook)

            // 6) Качество: больше 40% тишины или стоп-кадра — не режем.
            val bad = QualityCheck.badFraction(
                audio.loudPerSec.drop(bounds.start.toInt()).take(bounds.length.toInt().coerceAtLeast(1)),
                frames.diffPerSecond(bounds.start, bounds.end),
            )
            details = details.copy(badFraction = bad)
            if (!force && !manual && QualityCheck.isBad(bad)) {
                dao.update(
                    m.copy(
                        status = MomentStatus.REJECTED, clipStartSec = bounds.start, clipEndSec = bounds.end,
                        details = details.toJson(),
                        error = "Отброшен: ${(bad * 100).toInt()}% клипа — тишина, пауза или стоп-кадр. Можно нарезать кнопкой",
                    ),
                )
                AppLog.i(TAG, "Клип $id отброшен: плохих секунд ${(bad * 100).toInt()}%")
                return Result.success()
            }

            // 7) Дубли: пересекается больше чем на 50% с уже нарезанным — повышаем оценку старого.
            if (!manual && handleDuplicate(m, bounds, details)) return Result.success()

            // 8) Кадрирование 9:16.
            val (frame, layoutUsed, layoutNote) = planFrame(settings.layout, frames, bounds)
            details = details.copy(layoutNote = layoutNote)

            // 9) Части клипа (хук в начале — по желанию), субтитры, подписи, громкость.
            val parts = buildList {
                val hook = bounds.hook
                if (settings.hookAtStart && !manual && hook != null) add(hook)
                add(TimeRange(bounds.start, bounds.end))
            }
            val clipWords = words.filter { it.end > bounds.start - 0.5 && it.start < bounds.end + 0.5 }
            val cues = if (settings.subtitles && clipWords.isNotEmpty()) Subtitles.cues(clipWords, 0.0, duration) else emptyList()
            stage(id, m.title, "Пишу подписи для публикации…")
            val texts = publishTexts(m, details, clipWords, settings.aiApiKey)
            details = details.copy(
                titles = texts.first.titles, hookText = texts.first.hook, description = texts.first.description,
                hashtags = texts.first.hashtags, captionsByAi = texts.second,
            )
            val gain = audio.lufs?.let { LufsMeter.gainFor(it.integrated(bounds.start, bounds.end), it.peak(bounds.start, bounds.end)) } ?: 1.0

            // 10) Рендер.
            guard(id, m.title)
            stage(id, m.title, "Делаю вертикальный клип 9:16…")
            val out = File(work, "clip.mp4")
            val plan = RenderPlan(
                parts = parts,
                layout = layoutUsed,
                inAspect = frames.aspect,
                frame = frame,
                cues = cues,
                hookText = if (settings.hookTextOnVideo) texts.first.hook else null,
                audioGain = gain.toFloat(),
                frameRate = frames.frameRate,
            )
            withTimeoutOrNull(STAGE_TIMEOUT_MS) {
                ClipRenderer.render(ctx, source, out, plan) { p -> stageAsync(id, m.title, "Делаю вертикальный клип 9:16… $p%") }
            } ?: throw UserFacingException("Обработка видео заняла больше 10 минут. Попробуйте режим «Заполнить» или качество 720p")
            val tDone = System.currentTimeMillis()

            val current = dao.get(id) ?: return Result.success() // пока резали — удалили
            stage(id, m.title, "Сохраняю в галерею…")
            current.clipUri?.let { Gallery.delete(ctx, Uri.parse(it)) } // старая версия клипа
            val uri = Gallery.saveToGallery(ctx, out, fileName(current))
            dao.update(
                current.copy(
                    status = MomentStatus.READY, clipUri = uri.toString(), clipSize = out.length(), error = null,
                    clipStartSec = bounds.start, clipEndSec = bounds.end, details = details.toJson(),
                    sourceFile = m.sourceFile, sourceStartSec = m.sourceStartSec, peakInSource = m.peakInSource,
                ),
            )
            val timing = "Готов за ${sec(tDone - started)} (исходник ${sec(tDownload - started)} · анализ и обработка ${sec(tDone - tDownload)})"
            AppLog.i(TAG, "Клип $id: $timing, режим ${layoutUsed.name}, громкость ×${"%.2f".format(Locale.US, gain)}")
            Notifier.clipReady(ctx, id, "${current.title}\n$timing", uri)
            Gallery.pruneSources(ctx)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { dao.setStatus(id, MomentStatus.FOUND, "Нарезка прервана") }
            throw e
        } catch (e: Throwable) {
            AppLog.e(TAG, "Нарезка клипа $id не удалась", e)
            val msg = when (e) {
                is UserFacingException -> e.message
                is androidx.media3.transformer.ExportException -> "Ошибка кодирования видео (${e.errorCodeName})"
                else -> e.message ?: e.toString()
            }
            dao.setStatus(id, MomentStatus.FAILED, msg)
        } finally {
            ClipProgress.clear(id)
            work.deleteRecursively() // временные файлы всегда удаляем
        }
        return Result.success()
    }

    /** Файл-исходник: у эфиров он уже есть, у готовых видео — скачиваем кусок с запасом. */
    private suspend fun ensureSource(m: MomentEntity, quality: Int, work: File): MomentEntity {
        val existing = m.sourceFile?.let(::File)
        if (existing != null && existing.exists()) return m
        if (m.isLive) throw UserFacingException("Кусок эфира уже удалён — нарезать заново нельзя")
        val downloaded = withTimeoutOrNull(STAGE_TIMEOUT_MS) {
            YtDlp.downloadSection(applicationContext, m.sourceUrl, m.startSec, m.endSec, quality, work) { p ->
                stageAsync(m.id, m.title, "Скачиваю кусок видео… " + (p?.let { "$it%" } ?: ""))
            }
        } ?: throw UserFacingException("Кусок видео качался дольше 10 минут — похоже, медленный интернет")
        val target = File(Gallery.sourcesDir(applicationContext), "vod_${m.id}.${downloaded.extension}")
        downloaded.copyTo(target, overwrite = true)
        val updated = m.copy(sourceFile = target.absolutePath, sourceStartSec = m.startSec)
        AppDatabase.get(applicationContext).moments().update(updated.copy(status = MomentStatus.CUTTING))
        return updated
    }

    private suspend fun transcribe(id: Long, title: String, pcm: FloatArray, peak: Double, s: app.clipradar.data.AppSettings): List<Word> {
        val model = runCatching { WhisperModel.valueOf(s.whisperModel) }.getOrDefault(WhisperModel.TINY)
        // Расшифровываем только окно вокруг пика (±90 с), а не весь исходник.
        val from = maxOf(0.0, peak - 90)
        val to = minOf(pcm.size / 16000.0, peak + 90)
        val slice = pcm.copyOfRange((from * 16000).toInt(), (to * 16000).toInt().coerceAtLeast((from * 16000).toInt()))
        return try {
            stage(id, title, "Расшифровываю речь (${model.name.lowercase()})…")
            app.clipradar.speech.ModelManager.ensure(applicationContext, model) { p ->
                stageAsync(id, title, "Скачиваю модель распознавания… $p%")
            }
            stage(id, title, "Расшифровываю речь (${TimeFormat.duration(to - from)} звука)…")
            Transcriber.transcribe(applicationContext, model, slice, s.speechLanguage).words
                .map { it.copy(start = it.start + from, end = it.end + from) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Речь не расшифрована — границы только по звуку, сценам и чату", e)
            emptyList()
        }
    }

    /** Выбирает раскладку кадра без чёрных полей. Возвращает план, режим и пояснение. */
    private fun planFrame(wanted: ClipLayout, frames: FrameAnalysis, b: ClipBounds): Triple<FramePlan, ClipLayout, String> {
        val inAspect = frames.aspect
        val (w, h) = CropPlanner.fillCropSize(inAspect, 9.0 / 16)
        val inClip = frames.samples.filter { it.t >= b.start - 1 && it.t <= b.end + 1 }
        val webcam = CropPlanner.detectWebcam(inClip)
        if (wanted == ClipLayout.BLUR) return Triple(FramePlan(w, h, emptyList()), ClipLayout.BLUR, "размытый фон")
        if (wanted == ClipLayout.STREAM && webcam != null) {
            val faces = inClip.flatMap { it.faces }.filter {
                it.box.cx in webcam.x..webcam.right && it.box.cy in webcam.y..webcam.bottom
            }
            val face = if (faces.isEmpty()) null else NRect(
                PeakFinder.median(faces.map { it.box.x }), PeakFinder.median(faces.map { it.box.y }),
                PeakFinder.median(faces.map { it.box.w }), PeakFinder.median(faces.map { it.box.h }),
            )
            val cam = if (face != null) CropPlanner.refineCamRect(webcam, face, frames.lumaFrames, 160, 90) else webcam
            val layout = CropPlanner.streamLayout(inAspect, cam, face)
            // Игра: центр следует за движением (не за вебкой).
            val gameTrack = CropPlanner.smartTrack(inClip.map { FrameSample(it.t, emptyList(), it.motionX) }, layout.game.w, followFaces = false)
            return Triple(
                FramePlan(w, h, emptyList(), layout.cam, layout.game, gameTrack, layout.camFraction),
                ClipLayout.STREAM,
                "стрим-раскладка: вебка найдена",
            )
        }
        // «Заполнить»: если есть вебка — следуем за действием, иначе за лицом.
        val track = CropPlanner.smartTrack(inClip, w, followFaces = webcam == null)
        val note = when {
            wanted == ClipLayout.STREAM -> "вебка не найдена — режим «Заполнить»"
            webcam != null -> "заполнить: кадр следует за действием"
            inClip.any { it.faces.isNotEmpty() } -> "заполнить: кадр следует за лицом"
            else -> "заполнить: по центру/за движением"
        }
        return Triple(FramePlan(w, h, track), ClipLayout.FILL, note)
    }

    /** Если такой клип уже есть — повышаем его оценку и удаляем новый. */
    private suspend fun handleDuplicate(m: MomentEntity, b: ClipBounds, d: MomentDetails): Boolean {
        val dao = AppDatabase.get(applicationContext).moments()
        fun abs(e: MomentEntity, start: Double, end: Double, details: MomentDetails): TimeRange? = when {
            !e.isLive -> TimeRange(e.sourceStartSec + start, e.sourceStartSec + end)
            details.sourceStartWall != null -> TimeRange(details.sourceStartWall / 1000.0 + start, details.sourceStartWall / 1000.0 + end)
            else -> null
        }
        val mine = abs(m, b.start, b.end, d) ?: return false
        for (o in dao.sameSource(m.sourceUrl, m.channel, m.id)) {
            if (o.status !in setOf(MomentStatus.READY, MomentStatus.QUEUED, MomentStatus.CUTTING)) continue
            if (o.clipEndSec <= o.clipStartSec) continue
            val other = abs(o, o.clipStartSec, o.clipEndSec, MomentDetails.parse(o.details)) ?: continue
            if (MomentMerger.isDuplicate(mine, other)) {
                val boosted = MomentMerger.boostedScore(o.score, m.score)
                dao.update(o.copy(score = boosted))
                dao.delete(m.id)
                m.sourceFile?.let { File(it).delete() }
                AppLog.i(TAG, "Момент ${m.id} — дубль клипа ${o.id} (пересечение > 50%): оценка ${o.score} → $boosted")
                return true
            }
        }
        return false
    }

    private suspend fun publishTexts(m: MomentEntity, d: MomentDetails, words: List<Word>, apiKey: String): Pair<app.clipradar.core.PublishTexts, Boolean> {
        val streamer = d.streamer ?: m.channel
        val platform = if (m.isLive || m.sourceUrl.contains("twitch")) "twitch" else "youtube"
        val ai = if (apiKey.isNotBlank()) {
            AiCaptioner.generate(apiKey, app.clipradar.core.SpeechText.speech(words).joinToString(" ") { it.text }, streamer, d.channelUrl, d.game, platform)
        } else {
            null
        }
        return (ai ?: CaptionWriter.generate(streamer, d.channelUrl, d.game, words, platform, d.chatHype)) to (ai != null)
    }

    private suspend fun guard(id: Long, title: String) {
        DeviceGuard.waitUntilOk(applicationContext) { reason ->
            stage(id, title, "⏸ Пауза: $reason")
            Notifier.info(applicationContext, "⏸ Нарезка на паузе", reason)
        }
    }

    private var lastNotify = 0L

    private suspend fun stage(id: Long, title: String, text: String) {
        ClipProgress.set(id, text)
        lastNotify = System.currentTimeMillis()
        runCatching { setForeground(foregroundInfo(title, text)) }
    }

    private fun stageAsync(id: Long, title: String, text: String) {
        ClipProgress.set(id, text)
        val now = System.currentTimeMillis()
        if (now - lastNotify < 2_000) return
        lastNotify = now
        runCatching { setForegroundAsync(foregroundInfo(title, text)) }
    }

    private fun foregroundInfo(title: String, text: String): ForegroundInfo {
        val n = Notifier.progress(applicationContext, "✂️ $text", title)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifier.ID_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifier.ID_WORK, n)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("Clip Radar", "Нарезаю клип")

    private fun sec(ms: Long): String = TimeFormat.duration(ms / 1000.0)
    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)

    private fun fileName(m: MomentEntity): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = (m.channel ?: m.title).replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(40).trim('_')
        return "ClipRadar_${name}_${stamp}_${m.id}.mp4"
    }

    companion object {
        const val KEY_MOMENT = "momentId"
        const val KEY_FORCE = "force"
        private const val TAG = "ClipWorker"
        private const val STAGE_TIMEOUT_MS = 10 * 60_000L
    }
}
