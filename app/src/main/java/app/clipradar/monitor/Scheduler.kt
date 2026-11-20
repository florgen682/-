package app.clipradar.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.clipradar.data.AppDatabase
import app.clipradar.data.Settings
import app.clipradar.live.StreamerMonitor
import app.clipradar.live.WatchService
import app.clipradar.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Включает нужный режим автослежения. */
object Scheduler {
    private const val PERIODIC = "streamer-check"

    /**
     * Вызывается при запуске приложения, после перезагрузки телефона и при изменении настроек.
     * WorkManager раз в 15 минут проверяет стримеров (и заодно «будит» службу, если Android её закрыл).
     */
    fun apply(context: Context) {
        val req = PeriodicWorkRequestBuilder<StreamerCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        // ACTION_GUARD также продолжает наблюдение за эфирами, которые шли до перезапуска.
        if (Settings.get(context).current.guardMode || WatchService.hasSavedChannels(context)) WatchService.guard(context)
    }
}

/** Проверка «Моих стримеров» раз в 15 минут (когда режим «Всегда на страже» выключен). */
class StreamerCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = Settings.get(ctx).current
        // Если служба «на страже» должна работать, но Android её закрыл — запускаем снова.
        if (settings.guardMode && !WatchService.running.value) {
            if (WatchService.guard(ctx)) return Result.success()
        }
        if (settings.guardMode && WatchService.running.value) return Result.success() // проверяет служба
        if (AppDatabase.get(ctx).streamers().watched().isEmpty()) return Result.success()
        return try {
            for (c in StreamerMonitor.check(ctx)) {
                val started = WatchService.watch(ctx, c.info.login, c.info.startedAt)
                if (c.isNewStream || !started) {
                    Notifier.streamStarted(
                        ctx, c.info.displayName, autoWatching = started,
                        tapIntent = if (started) null else WatchService.tapToWatchIntent(ctx, c.info.login),
                    )
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

/** Запуск после перезагрузки телефона и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                Scheduler.apply(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
