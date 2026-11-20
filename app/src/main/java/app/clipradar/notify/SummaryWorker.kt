package app.clipradar.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.clipradar.data.Settings

/** Утром (после режима «тихо ночью») присылает отложенные итоги стримов. */
class SummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val list = Settings.get(applicationContext).takePendingSummaries()
        when {
            list.isEmpty() -> Unit
            list.size == 1 -> Notifier.postSummary(applicationContext, list.first())
            else -> Notifier.postSummary(applicationContext, "Пока вы спали:\n" + list.joinToString("\n\n"))
        }
        return Result.success()
    }
}
