package app.clipradar.live

import android.content.Context
import app.clipradar.core.BurstRule
import app.clipradar.core.ChatActivityTracker
import app.clipradar.core.ChatSnapshot
import app.clipradar.core.Cooldown
import app.clipradar.core.FeedbackTuner
import app.clipradar.core.InterestScorer
import app.clipradar.core.LiveLogic
import app.clipradar.core.Loudness
import app.clipradar.core.MomentSignals
import app.clipradar.core.SegmentRing
import app.clipradar.core.Sources
import app.clipradar.core.TimeFormat
import app.clipradar.data.AppDatabase
import app.clipradar.data.MomentDetails
import app.clipradar.data.MomentEntity
import app.clipradar.data.MomentStatus
import app.clipradar.data.Settings
import app.clipradar.media.AudioAnalyzer
import app.clipradar.media.Gallery
import app.clipradar.media.YtDlp
import app.clipradar.notify.Notifier
import app.clipradar.util.AppLog
import app.clipradar.work.ClipQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/** Короткая сводка для уведомления и экрана. */
data class SessionStatus(
    val channel: String,
    val chatConnected: Boolean,
    val chatPer10s: Int,
    val baseline: Double,
    val bufferSec: Int,
    val moments: Int,
)

/**
 * Наблюдение за одним эфиром Twitch:
 *  - пишет видео в кольцевой буфер последних 3 минут;
 *  - слушает чат и ищет «взрывы» активности;
 *  - на каждый момент ставит оценку и (если она выше порога) отправляет клип на нарезку;
 *  - в конце стрима присылает итог и удаляет буфер.
 */
class LiveSession(
    private val context: Context,
    val channel: String,
    private val streamStartedAt: Long?,
    private val displayName: String? = null,
    private val game: String? = null,
    /** Вызывается в конце; второй параметр — true, если остановил пользователь. */
    private val onFinished: (LiveSession, Boolean) -> Unit,
) {
    /** Момент, который ещё «идёт»: ждём, пока реакция утихнет (и поглощаем всплески ближе 20 с). */
    private class OpenMoment(val firstMs: Long, var peakMs: Long, var peakSnap: ChatSnapshot, var lastHotMs: Long)

    val sessionId = System.currentTimeMillis()
    private val settings = Settings.get(context)
    private val tracker = ChatActivityTracker()
    private val ring = SegmentRing(keepMs = 180_000) // буфер эфира — максимум 3 минуты
    private val bufferDir = File(context.cacheDir, "live/$channel")
    private val recorder = HlsRecorder(bufferDir, ring, settings.current.quality.coerceAtMost(1080)) {
        YtDlp.liveStreamUrl(context, Sources.twitchChannelUrl(channel), settings.current.quality.coerceAtMost(1080))
    }
    private val chat = TwitchChat(channel) { line -> tracker.add(System.currentTimeMillis(), line.user, line.text) }
    private val cooldown = Cooldown(settings.current.cooldownSec * 1000L)
    private val pending = Collections.synchronizedList(ArrayList<Job>())
    private var job: Job? = null
    @Volatile private var lastSnapshot: ChatSnapshot? = null
    @Volatile private var momentsFound = 0
    @Volatile private var stoppedByUser = false
    @Volatile private var openMoment: OpenMoment? = null
    @Volatile private var thresholdAdjust = 0

    fun status(): SessionStatus {
        val s = lastSnapshot
        val all = ring.all()
        val buffered = if (all.isEmpty()) 0 else ((all.last().endMs - all.first().startMs) / 1000).toInt()
        return SessionStatus(channel, chat.connected, s?.weighted ?: 0, s?.baseline ?: 0.0, buffered, momentsFound)
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.Default) {
            try {
                bufferDir.deleteRecursively()
                bufferDir.mkdirs()
                YtDlp.ensureInit(context)
                tracker.start(System.currentTimeMillis())
                AppLog.i(TAG, "$channel: начинаю наблюдение")
                coroutineScope {
                    val sessionScope = this
                    val chatJob = launch { chat.run() }
                    val detectJob = launch { detectLoop(sessionScope) }
                    val end = recorder.run()
                    AppLog.i(TAG, "$channel: запись закончилась: $end")
                    detectJob.cancel()
                    // Момент, который ещё «шёл», доделываем с тем, что есть в буфере.
                    openMoment?.let { o -> openMoment = null; launchFinalize(sessionScope, o) }
                    // Клипы в процессе — доделываем (не дольше 3 минут).
                    withTimeoutOrNull(180_000) { pending.toList().joinAll() }
                    pending.toList().forEach { it.cancel() }
                    chatJob.cancel()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "$channel: сессия упала", e)
            } finally {
                withContext(NonCancellable) {
                    bufferDir.deleteRecursively()
                    sendSummary()
                    onFinished(this@LiveSession, stoppedByUser)
                }
            }
        }
    }

    /** Остановить наблюдение (кнопка «Стоп»). */
    suspend fun stop() {
        stoppedByUser = true
        job?.cancelAndJoin()
    }

    private suspend fun detectLoop(sessionScope: CoroutineScope) {
        var lastPrefsCheck = 0L
        while (currentCoroutineContext().isActive) {
            delay(1_000)
            val now = System.currentTimeMillis()
            if (now - lastPrefsCheck > 60_000) {
                lastPrefsCheck = now
                thresholdAdjust = runCatching {
                    AppDatabase.get(context).channelPrefs().get("twitch:$channel")?.thresholdAdjust ?: 0
                }.getOrDefault(0)
            }
            val snap = tracker.snapshot(now)
            lastSnapshot = snap
            val s = settings.current
            cooldown.pauseMs = s.cooldownSec * 1000L
            val base = BurstRule.forSensitivity(s.chatSensitivity)
            val rule = base.copy(multiplier = base.multiplier * FeedbackTuner.chatMultiplierScale(thresholdAdjust))
            val burst = rule.isBurst(snap)
            val o = openMoment
            if (o == null) {
                if (burst && cooldown.tryAcquire(now)) {
                    openMoment = OpenMoment(now, now, snap, now)
                    momentsFound++
                    Notifier.moment(
                        context, channel,
                        "Чат взорвался: ${snap.weighted} сообщений за 10 сек (в ${"%.1f".format(Locale.US, snap.ratio)} раза больше обычного). Жду конца реакции…",
                    )
                }
                continue
            }
            // Реакция продолжается, пока чат выше обычного; новые всплески ближе 20 с — тот же момент.
            if (burst || snap.ratio > 1.3) o.lastHotMs = now
            if (snap.ratio > o.peakSnap.ratio) {
                o.peakMs = now
                o.peakSnap = snap
            }
            val calmFor = now - o.lastHotMs
            val tooLong = now - o.firstMs > 120_000 // буфер 3 мин, а нам нужно 90 с до момента
            if (calmFor >= 20_000 || tooLong) {
                openMoment = null
                launchFinalize(sessionScope, o)
            }
        }
    }

    private fun launchFinalize(scope: CoroutineScope, o: OpenMoment) {
        val j = scope.launch {
            try {
                finalizeMoment(o, System.currentTimeMillis())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "$channel: не удалось обработать момент", e)
            }
        }
        pending += j
        j.invokeOnCompletion { pending.remove(j) }
    }

    /** Сохраняет окно эфира вокруг момента (до 90 с до него), оценивает и ставит в очередь нарезки. */
    private suspend fun finalizeMoment(o: OpenMoment, nowMs: Long) {
        val s = settings.current
        val all = ring.all()
        if (all.isEmpty()) {
            AppLog.w(TAG, "$channel: в буфере пусто — момент пропущен")
            return
        }
        val from = maxOf(all.first().startMs, o.firstMs - 90_000)
        val plan = LiveLogic.planClip(all, o.peakMs, o.peakMs - from, nowMs - o.peakMs) ?: return
        val peakInSource = plan.momentOffsetSec
        // Склеиваем куски в один файл — «сырой» исходник (останется для ручной правки).
        val init = recorder.initFile
        val ext = if (init != null) "mp4" else "ts"
        val source = File(Gallery.sourcesDir(context), "live_${channel}_${o.peakMs}.$ext")
        withContext(Dispatchers.IO) {
            source.outputStream().use { out ->
                if (init != null && init.exists()) init.inputStream().use { it.copyTo(out) }
                for (seg in plan.segments) {
                    val f = File(seg.file)
                    if (f.exists()) f.inputStream().use { it.copyTo(out) }
                }
            }
        }
        val chatPerSec = tracker.perSecond(plan.sourceStartMs, plan.sourceStartMs + (plan.durationSec * 1000).toLong())
        val loud = AudioAnalyzer.loudnessPerSecond(source)
        val jump = Loudness.jumpDb(loud, peakInSource, before = 10, after = 5)
        val snap = o.peakSnap
        val score = InterestScorer.score(
            MomentSignals(snap.ratio, snap.hypeShare, snap.uniqueUsers, jump, chatBaseline = snap.baseline),
        )
        val threshold = FeedbackTuner.threshold(s.scoreThreshold, thresholdAdjust)
        val status = when {
            score.score < threshold -> MomentStatus.WEAK
            s.autoCut -> MomentStatus.QUEUED
            else -> MomentStatus.FOUND
        }
        val details = MomentDetails(
            chatPerSec = chatPerSec,
            chatBaseline = snap.baseline / 10.0, // норма была «за 10 секунд»
            chatHype = snap.hypeShare,
            sourceStartWall = plan.sourceStartMs,
            streamer = displayName ?: channel,
            channelUrl = Sources.twitchChannelUrl(channel),
            game = game,
        )
        val id = AppDatabase.get(context).moments().insert(
            MomentEntity(
                sourceUrl = Sources.twitchChannelUrl(channel),
                title = "Стрим ${displayName ?: channel}",
                channel = channel,
                isLive = true,
                sessionId = sessionId,
                positionSec = streamStartedAt?.let { (o.peakMs - it) / 1000.0 } ?: -1.0,
                wallTime = o.peakMs,
                sourceFile = source.absolutePath,
                peakInSource = peakInSource,
                score = score.score,
                reason = score.reason + if (o.lastHotMs - o.firstMs > 25_000) " (несколько всплесков — объединены)" else "",
                status = status,
                details = details.toJson(),
            ),
        )
        AppLog.i(TAG, "$channel: момент $id, оценка ${score.score} (порог $threshold), окно ${plan.durationSec.toInt()} с")
        if (status == MomentStatus.QUEUED) ClipQueue.enqueue(context, id)
    }

    private suspend fun sendSummary() {
        val list = runCatching { AppDatabase.get(context).moments().bySession(sessionId) }.getOrDefault(emptyList())
        val cut = list.count { it.status in setOf(MomentStatus.QUEUED, MomentStatus.CUTTING, MomentStatus.READY) }
        val best = list.maxByOrNull { it.score }
        val head = if (stoppedByUser) "Наблюдение за $channel остановлено" else "Стрим $channel закончился"
        val text = if (best == null) {
            "$head: интересных моментов не найдено"
        } else {
            "$head: найдено ${list.size} ${plural(list.size)}, нарезано $cut ${clipsWord(cut)}, " +
                "лучший — ${momentTime(best)}, оценка ${best.score}"
        }
        Notifier.summary(context, text)
    }

    companion object {
        private const val TAG = "LiveSession"

        fun momentTime(m: MomentEntity): String {
            val clock = m.wallTime?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }
            val pos = if (m.positionSec >= 0) TimeFormat.duration(m.positionSec) else null
            return when {
                clock != null && pos != null -> "$clock ($pos от начала)"
                clock != null -> clock
                pos != null -> pos
                else -> "—"
            }
        }

        private fun plural(n: Int) = when {
            n % 10 == 1 && n % 100 != 11 -> "момент"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "момента"
            else -> "моментов"
        }

        private fun clipsWord(n: Int) = when {
            n % 10 == 1 && n % 100 != 11 -> "клип"
            n % 10 in 2..4 && n % 100 !in 12..14 -> "клипа"
            else -> "клипов"
        }
    }
}
