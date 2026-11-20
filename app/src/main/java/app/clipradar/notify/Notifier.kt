package app.clipradar.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.clipradar.MainActivity
import app.clipradar.R
import app.clipradar.core.QuietHours
import app.clipradar.data.Settings
import app.clipradar.media.Gallery
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

object Notifier {
    const val CH_WATCH = "watch"
    const val CH_MOMENTS = "moments"
    const val CH_CLIPS = "clips"
    const val CH_SUMMARY = "summary"
    const val CH_WORK = "work"
    const val CH_WARN = "warnings"

    const val ID_WATCH = 1
    const val ID_WORK = 2
    const val ID_ANALYZE = 3
    private const val ID_STORAGE = 4

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_WATCH, "Наблюдение за эфиром", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_MOMENTS, "Моменты и начало стримов", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_CLIPS, "Готовые клипы", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_SUMMARY, "Итоги стримов", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_WORK, "Нарезка и анализ", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_WARN, "Предупреждения", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    /** Сейчас «тихое» время? */
    fun isQuietNow(context: Context): Boolean {
        val s = Settings.get(context).current
        return s.quietEnabled && QuietHours.isQuiet(LocalDateTime.now(), s.quietStartMin, s.quietEndMin)
    }

    fun openAppIntent(context: Context, requestCode: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun base(context: Context, channel: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFF9B6BFF.toInt())
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)

    private fun post(context: Context, id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** 🔥 Момент! */
    fun moment(context: Context, channel: String, text: String) {
        if (isQuietNow(context)) return
        post(
            context, ("m" + channel + System.currentTimeMillis() / 1000).hashCode(),
            base(context, CH_MOMENTS)
                .setContentTitle("🔥 Момент! — $channel")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .build(),
        )
    }

    /** 🔴 Стример начал эфир. */
    fun streamStarted(context: Context, nick: String, autoWatching: Boolean, tapIntent: PendingIntent? = null) {
        val text = if (autoWatching) "Слежу за эфиром и нарезаю лучшие моменты" else "Нажмите, чтобы начать наблюдение"
        if (autoWatching && isQuietNow(context)) return
        post(
            context, ("live_$nick").hashCode(),
            base(context, CH_MOMENTS)
                .setContentTitle("🔴 $nick начал стрим")
                .setContentText(text)
                .apply { if (tapIntent != null) setContentIntent(tapIntent) }
                .build(),
        )
    }

    /** ✅ Клип готов — с кнопками «Открыть» и «Поделиться». */
    fun clipReady(context: Context, momentId: Long, title: String, stored: Uri) {
        if (isQuietNow(context)) return
        val uri = Gallery.shareableUri(context, stored)
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val share = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Поделиться клипом",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(context, (momentId * 2).toInt(), open, flags)
        val sharePi = PendingIntent.getActivity(context, (momentId * 2 + 1).toInt(), share, flags)
        post(
            context, (10_000 + momentId).toInt(),
            base(context, CH_CLIPS)
                .setContentTitle("✅ Клип готов")
                .setContentText(title)
                .setContentIntent(openPi)
                .addAction(0, "Открыть", openPi)
                .addAction(0, "Поделиться", sharePi)
                .build(),
        )
    }

    /** Итог стрима. Ночью — откладываем до утра. */
    fun summary(context: Context, text: String) {
        if (isQuietNow(context)) {
            Settings.get(context).addPendingSummary(text)
            scheduleMorningSummary(context)
            return
        }
        postSummary(context, text)
    }

    fun postSummary(context: Context, text: String) {
        post(
            context, ("s" + text).hashCode(),
            base(context, CH_SUMMARY)
                .setContentTitle("📊 Итог стрима")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .build(),
        )
    }

    private fun scheduleMorningSummary(context: Context) {
        val s = Settings.get(context).current
        val now = LocalDateTime.now()
        val delay = Duration.between(now, QuietHours.nextEnd(now, s.quietEndMin)).toMillis().coerceAtLeast(60_000)
        val req = OneTimeWorkRequestBuilder<SummaryWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("morning-summary", ExistingWorkPolicy.REPLACE, req)
    }

    fun info(context: Context, title: String, text: String) {
        post(
            context, ("i" + title + text).hashCode(),
            base(context, CH_WARN).setContentTitle(title).setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text)).build(),
        )
    }

    fun lowStorage(context: Context) {
        post(
            context, ID_STORAGE,
            base(context, CH_WARN)
                .setContentTitle("⚠️ Мало места")
                .setContentText("Свободно меньше 1 ГБ — клипы не режутся. Удалите старые клипы в приложении.")
                .build(),
        )
    }

    fun progress(context: Context, title: String, text: String) =
        NotificationCompat.Builder(context, CH_WORK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(openAppIntent(context))
            .build()
}
