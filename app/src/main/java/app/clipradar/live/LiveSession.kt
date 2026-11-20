package app.clipradar.live

import android.content.Context
import android.util.Log
import app.clipradar.core.BurstRule
import app.clipradar.core.ChatActivityTracker
import app.clipradar.core.ChatSnapshot
import app.clipradar.core.Cooldown
import app.clipradar.core.InterestScorer
import app.clipradar.core.LiveLogic
import app.clipradar.core.Loudness
import app.clipradar.core.MomentSignals
import app.clipradar.core.SegmentRing
import app.clipradar.core.Sources
import app.clipradar.core.TimeFormat
import app.clipradar.data.AppDatabase
import app.clipradar.data.MomentEntity
import app.clipradar.data.MomentStatus
import app.clipradar.data.Settings
import app.clipradar.media.AudioAnalyzer
import app.clipradar.media.Gallery
import app.clipradar.media.YtDlp
import app.clipradar.notify.Notifier
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
    /** Вызывается в конце; второй параметр — true, если остановил пользователь. */
    private val onFinished: (LiveSession, Boolean) -> Unit,
) {
    val sessionId = System.currentTimeMillis()
    private val settings = Settings.get(context)
    private val tracker = ChatActivityTracker()
    private val ring = SegmentRing(keepMs = 180_000)
    private val bufferDir = File(context.cacheDir, "live/$channel")
    private val recorder = HlsRecorder(bufferDir, ring, settings.current.quality.coerceAtMost(720)) {
        YtDlp.liveStreamUrl(context, Sources.twitchChannelUrl(channel), settings.current.quality.coerceAtMost(720))
    }
    private val chat = TwitchChat(channel) { line -> tracker.add(System.currentTimeMillis(), line.user, line.text) }
    private val cooldown = Cooldown(settings.current.cooldownSec * 1000L)
    private val pending = Collections.synchronizedList(ArrayList<Job>())
    private var job: Job? = null
    @Volatile private var lastSnapshot: ChatSnapshot? = null
    @Volatile private var momentsFound = 0
    @Volatile private var stoppedByUser = false

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
                coroutineScope {
                    val sessionScope = this
                    val chatJob = launch { chat.run() }
                    val detectJob = launch { detectLoop(sessionScope) }
                    val end = recorder.run()
                    Log.i(TAG, "$channel: запись закончилась: $end")
                    detectJob.cancel()
                    // Даём уже найденным моментам доделаться (не дольше минуты).
                    withTimeoutOrNull(60_000) { pending.toList().joinAll() }
                    pending.toList().forEach { it.cancel() }
                    chatJob.cancel()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$channel: сессия упала", e)
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
        while (currentCoroutineContext().isActive) {
            delay(1_000)
            val now = System.currentTimeMillis()
            val snap = tracker.snapshot(now)
            lastSnapshot = snap
            val s = settings.current
            cooldown.pauseMs = s.cooldownSec * 1000L
            if (BurstRule.forSensitivity(s.chatSensitivity).isBurst(snap) && cooldown.tryAcquire(now)) {
                momentsFound++
                val j = sessionScope.launch {
                    try {
                        processMoment(now, snap)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "$channel: не удалось обработать момент", e)
                    }
                }
                pending += j
                j.invokeOnCompletion { pending.remove(j) }
            }
        }
    }

    private suspend fun processMoment(momentMs: Long, snap: ChatSnapshot) {
        val s = settings.current
        Notifier.moment(
            context, channel,
            "Чат взорвался: ${snap.weighted} сообщений за 10 сек (в ${"%.1f".format(Locale.US, snap.ratio)} раза больше обычного). Готовлю клип…",
        )
        // Ждём, пока в буфер попадёт продолжение (15 сек после момента + запас).
        delay(s.liveAfterSec * 1000L + 4_000)
        val plan = LiveLogic.planClip(ring.all(), momentMs, s.liveBeforeSec * 1000L, s.liveAfterSec * 1000L)
        if (plan == null) {
            Log.w(TAG, "$channel: в буфере пусто — момент пропущен")
            return
        }
        // Склеиваем куски в один файл (это «сырой» исходник клипа).
        val init = recorder.initFile
        val ext = if (init != null) "mp4" else "ts"
        val source = File(Gallery.sourcesDir(context), "live_${channel}_$momentMs.$ext")
        withContext(Dispatchers.IO) {
            source.outputStream().use { out ->
                if (init != null && init.exists()) init.inputStream().use { it.copyTo(out) }
                for (seg in plan.segments) {
                    val f = File(seg.file)
                    if (f.exists()) f.inputStream().use { it.copyTo(out) }
                }
            }
        }
        // Скачок громкости вокруг момента.
        val loud = AudioAnalyzer.loudnessPerSecond(source)
        val jump = Loudness.jumpDb(loud, plan.momentOffsetSec, before = 10, after = 5)
        val score = InterestScorer.score(
            MomentSignals(snap.ratio, snap.hypeShare, snap.uniqueUsers, jump, chatBaseline = snap.baseline),
        )

        val good = score.score >= s.scoreThreshold
        val status = when {
            !good -> MomentStatus.WEAK
            s.autoCut -> MomentStatus.QUEUED
            else -> MomentStatus.FOUND
        }
        val dao = AppDatabase.get(context).moments()
        val id = dao.insert(
            MomentEntity(
                sourceUrl = Sources.twitchChannelUrl(channel),
                title = "Стрим $channel",
                channel = channel,
                isLive = true,
                sessionId = sessionId,
                positionSec = streamStartedAt?.let { (momentMs - it) / 1000.0 } ?: -1.0,
                wallTime = momentMs,
                sourceFile = source.absolutePath,
                score = score.score,
                reason = score.reason,
                status = status,
            ),
        )
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
