package app.clipradar.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.clipradar.core.SourceKind
import app.clipradar.core.Sources
import app.clipradar.data.AppDatabase
import app.clipradar.data.AppSettings
import app.clipradar.data.MomentEntity
import app.clipradar.data.MomentStatus
import app.clipradar.data.Settings
import app.clipradar.data.StreamerEntity
import app.clipradar.live.SessionStatus
import app.clipradar.live.WatchService
import app.clipradar.media.Gallery
import app.clipradar.media.YtDlp
import app.clipradar.monitor.Scheduler
import app.clipradar.work.AnalyzeWorker
import app.clipradar.work.ClipQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class StorageInfo(val clipsBytes: Long = 0, val sourcesBytes: Long = 0, val freeBytes: Long = Long.MAX_VALUE) {
    val lowSpace: Boolean get() = freeBytes < Gallery.MIN_FREE_BYTES
}

/** Что сейчас делает поиск моментов в готовом видео. */
data class AnalyzeState(val running: Boolean, val stage: String?, val error: String?, val result: String?)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val db = AppDatabase.get(ctx)
    private val settingsStore = Settings.get(ctx)

    val settings: StateFlow<AppSettings> = settingsStore.state
    val moments = db.moments().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val streamers = db.streamers().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val sessions: StateFlow<List<SessionStatus>> = WatchService.sessionsFlow

    val analyze: StateFlow<AnalyzeState?> = WorkManager.getInstance(ctx)
        .getWorkInfosForUniqueWorkFlow(AnalyzeWorker.UNIQUE)
        .map { infos -> toAnalyzeState(infos) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _storage = MutableStateFlow(StorageInfo())
    val storage: StateFlow<StorageInfo> = _storage.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _ytdlpVersion = MutableStateFlow("—")
    val ytdlpVersion: StateFlow<String> = _ytdlpVersion.asStateFlow()

    init {
        viewModelScope.launch { moments.collect { refreshStorage() } }
        viewModelScope.launch {
            runCatching { YtDlp.ensureInit(ctx) }
            _ytdlpVersion.value = YtDlp.version(ctx)
        }
    }

    private fun toAnalyzeState(infos: List<WorkInfo>): AnalyzeState? {
        val active = infos.firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        if (active != null) {
            val stage = active.progress.getString(AnalyzeWorker.KEY_STAGE)
                ?: if (active.state == WorkInfo.State.ENQUEUED) "В очереди…" else "Запускаю…"
            return AnalyzeState(true, stage, null, null)
        }
        val last = infos.lastOrNull { it.state.isFinished } ?: return null
        return AnalyzeState(
            false, null,
            last.outputData.getString(AnalyzeWorker.KEY_ERROR),
            last.outputData.getString(AnalyzeWorker.KEY_RESULT),
        )
    }

    private fun say(text: String) {
        _messages.tryEmit(text)
    }

    // ---------- Ссылки ----------

    fun findMoments(input: String) {
        val url = Sources.extractUrl(input) ?: input.trim()
        when (Sources.kind(url)) {
            SourceKind.INVALID -> say("Вставьте ссылку на видео YouTube или Twitch")
            SourceKind.TWITCH_CHANNEL -> say("Это канал Twitch — для прямого эфира нажмите «Следить за эфиром»")
            else -> {
                AnalyzeWorker.start(ctx, url)
                say("Ищу лучшие моменты… Это может занять пару минут")
            }
        }
    }

    /** true — наблюдение запущено (нужно попросить исключение из экономии батареи). */
    fun watchLive(input: String): Boolean {
        val url = Sources.extractUrl(input) ?: input.trim()
        return when (Sources.kind(url)) {
            SourceKind.TWITCH_CHANNEL -> {
                val nick = Sources.twitchChannel(url)!!
                if (WatchService.watch(ctx, nick)) {
                    say("Слежу за эфиром $nick")
                    true
                } else {
                    say("Android не разрешил запустить наблюдение")
                    false
                }
            }
            SourceKind.YOUTUBE -> {
                say("Прямые эфиры YouTube пока не поддерживаются — пока поддерживается только Twitch")
                false
            }
            SourceKind.TWITCH_VOD -> {
                say("Это запись стрима — нажмите «Найти моменты»")
                false
            }
            else -> {
                say("Вставьте ссылку на канал Twitch или просто ник")
                false
            }
        }
    }

    fun stopWatching(channel: String) = WatchService.stopChannel(ctx, channel)
    fun stopAll() = WatchService.stopAll(ctx)

    // ---------- Моменты ----------

    fun cut(m: MomentEntity) = viewModelScope.launch {
        if (m.isLive && (m.sourceFile == null || !File(m.sourceFile).exists())) {
            say("Кусок эфира уже удалён — нарезать заново нельзя")
            return@launch
        }
        if (!Gallery.hasEnoughSpace(ctx)) {
            say("Мало места: свободно меньше 1 ГБ. Удалите старые клипы")
            return@launch
        }
        ClipQueue.enqueue(ctx, m.id)
        say("Клип поставлен в очередь на нарезку")
    }

    fun delete(m: MomentEntity) = viewModelScope.launch(Dispatchers.IO) {
        deleteMomentFiles(m)
        db.moments().delete(m.id)
    }

    private fun deleteMomentFiles(m: MomentEntity) {
        m.clipUri?.let { Gallery.delete(ctx, Uri.parse(it)) }
        m.sourceFile?.let { File(it).delete() }
    }

    /** «Удалить старые»: [olderThanDays] — старше N дней, либо оставить [keepLast] последних. */
    fun deleteOld(olderThanDays: Int? = null, keepLast: Int? = null) = viewModelScope.launch(Dispatchers.IO) {
        val all = db.moments().all().filter { it.status != MomentStatus.CUTTING && it.status != MomentStatus.QUEUED }
        val victims = when {
            olderThanDays != null -> {
                val limit = System.currentTimeMillis() - olderThanDays * 24 * 3600_000L
                all.filter { it.createdAt < limit }
            }
            keepLast != null -> all.sortedByDescending { it.createdAt }.drop(keepLast)
            else -> emptyList()
        }
        for (m in victims) {
            deleteMomentFiles(m)
            db.moments().delete(m.id)
        }
        say(if (victims.isEmpty()) "Нечего удалять" else "Удалено: ${victims.size}")
        refreshStorage()
    }

    fun refreshStorage() = viewModelScope.launch(Dispatchers.IO) {
        val clips = moments.value.filter { it.status == MomentStatus.READY }.sumOf { it.clipSize }
        val sources = Gallery.dirSize(Gallery.sourcesDir(ctx))
        _storage.value = StorageInfo(clips, sources, Gallery.freeBytes(ctx))
    }

    // ---------- Стримеры ----------

    /** true — стример добавлен. */
    fun addStreamer(input: String): Boolean {
        val nick = Sources.twitchChannel(input)
        if (nick == null) {
            say("Не похоже на ник или ссылку Twitch-канала")
            return false
        }
        viewModelScope.launch {
            db.streamers().insert(StreamerEntity(login = nick, displayName = nick))
            Scheduler.apply(ctx)
            say("Добавлен: $nick")
        }
        return true
    }

    fun setWatch(s: StreamerEntity, watch: Boolean) = viewModelScope.launch {
        db.streamers().update(s.copy(watch = watch))
        Scheduler.apply(ctx)
    }

    fun removeStreamer(s: StreamerEntity) = viewModelScope.launch { db.streamers().delete(s.login) }

    fun watchStreamerNow(s: StreamerEntity) {
        if (WatchService.watch(ctx, s.login)) say("Слежу за эфиром ${s.displayName}")
    }

    fun setGuard(on: Boolean) {
        settingsStore.update { it.copy(guardMode = on) }
        // Выключили — служба сама остановится, если ей больше нечего делать.
        if (on || WatchService.running.value) WatchService.guard(ctx)
        Scheduler.apply(ctx)
    }

    // ---------- Настройки ----------

    fun updateSettings(change: (AppSettings) -> AppSettings) = settingsStore.update(change)

    fun updateYtDlp() = viewModelScope.launch {
        say("Обновляю yt-dlp…")
        val text = runCatching { YtDlp.update(ctx) }.getOrElse { "Не удалось обновить: ${it.message}" }
        settingsStore.lastYtDlpUpdate = System.currentTimeMillis()
        _ytdlpVersion.value = YtDlp.version(ctx)
        withContext(Dispatchers.Main) { say(text) }
    }
}
