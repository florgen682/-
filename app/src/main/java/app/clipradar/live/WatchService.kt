package app.clipradar.live

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.clipradar.R
import app.clipradar.data.AppDatabase
import app.clipradar.data.Settings
import app.clipradar.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Служба, которая работает в фоне с постоянным уведомлением:
 *  - следит за прямыми эфирами (по одной [LiveSession] на канал);
 *  - в режиме «Всегда на страже» каждые 2 минуты проверяет «Моих стримеров».
 */
class WatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessions = ConcurrentHashMap<String, LiveSession>()
    private var guardJob: Job? = null
    private var statusJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val settings by lazy { Settings.get(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android требует показать уведомление сразу после запуска службы.
        startAsForeground()
        when (intent?.action) {
            ACTION_WATCH -> intent.getStringExtra(EXTRA_CHANNEL)?.let {
                val started = intent.getLongExtra(EXTRA_STARTED_AT, -1).takeIf { t -> t > 0 }
                startSession(it.lowercase(), started)
            }
            ACTION_STOP -> {
                scope.launch { stopEverything() }
                return START_NOT_STICKY
            }
            ACTION_STOP_CHANNEL -> intent.getStringExtra(EXTRA_CHANNEL)?.let { ch ->
                scope.launch { stopChannel(ch) }
            }
            else -> restoreAfterRestart() // ACTION_GUARD или перезапуск системой (intent == null)
        }
        updateGuard()
        ensureStatusLoop()
        scope.launch { delay(3_000); maybeStopSelf() }
        return START_STICKY
    }

    private fun startAsForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_WATCH, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_WATCH, n)
        }
    }

    private fun startSession(channel: String, startedAt: Long?) {
        if (sessions.containsKey(channel)) return
        val session = LiveSession(applicationContext, channel, startedAt) { s, byUser ->
            sessions.remove(s.channel, s)
            if (!byUser) removePersisted(s.channel)
            publishStatus()
            scope.launch { maybeStopSelf() }
        }
        sessions[channel] = session
        persist(channel)
        acquireLocks()
        session.start(scope)
        publishStatus()
    }

    private suspend fun stopChannel(channel: String) {
        val s = sessions[channel] ?: return
        StreamerMonitor.ignoreCurrentStream(applicationContext, channel)
        removePersisted(channel)
        s.stop()
        sessions.remove(channel)
        publishStatus()
        maybeStopSelf()
    }

    private suspend fun stopEverything() {
        // «Стоп» выключает и наблюдение, и режим «на страже».
        settings.update { it.copy(guardMode = false) }
        guardJob?.cancel()
        guardJob = null
        for (ch in sessions.keys.toList()) {
            StreamerMonitor.ignoreCurrentStream(applicationContext, ch)
            sessions[ch]?.stop()
        }
        sessions.clear()
        clearPersisted()
        publishStatus()
        stopSelfNow()
    }

    /** После перезапуска (Android закрыл приложение) продолжаем наблюдение. */
    private fun restoreAfterRestart() {
        val saved = persisted()
        if (saved.isEmpty()) return
        scope.launch {
            val infos = runCatching { TwitchApi.check(applicationContext, saved.toList()) }.getOrDefault(emptyMap())
            for (ch in saved) {
                val info = infos[ch]
                if (info == null || info.live) startSession(ch, info?.startedAt) else removePersisted(ch)
            }
            maybeStopSelf()
        }
    }

    private fun updateGuard() {
        if (settings.current.guardMode) {
            if (guardJob?.isActive != true) {
                acquireLocks()
                guardJob = scope.launch {
                    while (isActive) {
                        runCatching { checkStreamers() }.onFailure { Log.w(TAG, "Проверка стримеров не удалась", it) }
                        delay(GUARD_INTERVAL_MS)
                        if (!settings.current.guardMode) break
                    }
                    guardJob = null
                    maybeStopSelf()
                }
            }
        } else {
            guardJob?.cancel()
            guardJob = null
        }
    }

    private suspend fun checkStreamers() {
        for (c in StreamerMonitor.check(applicationContext)) {
            if (c.isNewStream) Notifier.streamStarted(applicationContext, c.info.displayName, autoWatching = true)
            startSession(c.info.login, c.info.startedAt)
        }
    }

    private fun ensureStatusLoop() {
        if (statusJob?.isActive == true) return
        statusJob = scope.launch {
            while (isActive) {
                publishStatus()
                runCatching {
                    androidx.core.app.NotificationManagerCompat.from(this@WatchService)
                        .notify(Notifier.ID_WATCH, buildNotification())
                }
                delay(5_000)
            }
        }
    }

    private fun publishStatus() {
        sessionsState.value = sessions.values.map { it.status() }.sortedBy { it.channel }
    }

    private suspend fun maybeStopSelf() {
        if (sessions.isEmpty() && !settings.current.guardMode && persisted().isEmpty()) stopSelfNow()
    }

    private fun stopSelfNow() {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(): Notification {
        val list = sessions.values.map { it.status() }
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, WatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = if (list.isNotEmpty()) {
            "Слежу за эфиром…" to list.joinToString("\n") { s ->
                val chat = if (s.chatConnected) "чат ${s.chatPer10s}/10с" else "подключаюсь к чату"
                "🔴 ${s.channel}: $chat, буфер ${s.bufferSec} с, моментов ${s.moments}"
            }
        } else {
            "На страже" to "Проверяю «Моих стримеров» каждые 2 минуты"
        }
        return NotificationCompat.Builder(this, Notifier.CH_WATCH)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull() ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(Notifier.openAppIntent(this))
            .addAction(0, "Стоп", stopPi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipRadar:watch").apply { acquire() }
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ClipRadar:watch").apply { acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    // Каналы, за которыми следим, сохраняем — чтобы продолжить после перезапуска.
    private val prefs by lazy { getSharedPreferences("watch", MODE_PRIVATE) }
    private fun persisted(): Set<String> = prefs.getStringSet("channels", emptySet())!!.toSet()
    private fun persist(ch: String) = prefs.edit().putStringSet("channels", persisted() + ch).apply()
    private fun removePersisted(ch: String) = prefs.edit().putStringSet("channels", persisted() - ch).apply()
    private fun clearPersisted() = prefs.edit().remove("channels").apply()

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        sessionsState.value = emptyList()
        running.value = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WatchService"
        private const val GUARD_INTERVAL_MS = 2 * 60_000L
        const val ACTION_WATCH = "app.clipradar.WATCH"
        const val ACTION_STOP = "app.clipradar.STOP"
        const val ACTION_STOP_CHANNEL = "app.clipradar.STOP_CHANNEL"
        const val ACTION_GUARD = "app.clipradar.GUARD"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_STARTED_AT = "startedAt"

        private val sessionsState = MutableStateFlow<List<SessionStatus>>(emptyList())
        val sessionsFlow: StateFlow<List<SessionStatus>> = sessionsState.asStateFlow()
        val running = MutableStateFlow(false)

        fun hasSavedChannels(context: Context): Boolean =
            context.getSharedPreferences("watch", Context.MODE_PRIVATE).getStringSet("channels", emptySet())!!.isNotEmpty()

        fun isWatching(channel: String) = sessionsState.value.any { it.channel == channel.lowercase() }

        fun watchIntent(context: Context, channel: String, startedAt: Long? = null) =
            Intent(context, WatchService::class.java)
                .setAction(ACTION_WATCH)
                .putExtra(EXTRA_CHANNEL, channel.lowercase())
                .putExtra(EXTRA_STARTED_AT, startedAt ?: -1L)

        /** Начать наблюдение за каналом. false — Android не дал запустить службу из фона. */
        fun watch(context: Context, channel: String, startedAt: Long? = null): Boolean = start(context, watchIntent(context, channel, startedAt))

        fun guard(context: Context): Boolean =
            start(context, Intent(context, WatchService::class.java).setAction(ACTION_GUARD))

        fun stopAll(context: Context) {
            if (!running.value) return
            start(context, Intent(context, WatchService::class.java).setAction(ACTION_STOP))
        }

        fun stopChannel(context: Context, channel: String) =
            start(context, Intent(context, WatchService::class.java).setAction(ACTION_STOP_CHANNEL).putExtra(EXTRA_CHANNEL, channel))

        private fun start(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // Android 12+ может запретить запуск из фона, если нет исключения из экономии батареи.
            Log.w(TAG, "Не удалось запустить службу: ${e.message}")
            false
        }

        /** Уведомление «нажмите, чтобы начать наблюдение» — запасной вариант, если служба не запустилась. */
        fun tapToWatchIntent(context: Context, channel: String): PendingIntent =
            PendingIntent.getForegroundService(
                context, channel.hashCode(), watchIntent(context, channel),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
