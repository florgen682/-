package app.clipradar

import android.app.Application
import android.util.Log
import app.clipradar.data.Settings
import app.clipradar.media.YtDlp
import app.clipradar.monitor.Scheduler
import app.clipradar.notify.Notifier
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClipRadarApp : Application(), ImageLoaderFactory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        appScope.launch {
            try {
                // Первый запуск распаковывает Python и yt-dlp — это занимает несколько секунд.
                YtDlp.ensureInit(this@ClipRadarApp)
                // Раз в сутки обновляем yt-dlp: YouTube и Twitch часто что-то меняют.
                val s = Settings.get(this@ClipRadarApp)
                if (System.currentTimeMillis() - s.lastYtDlpUpdate > 24 * 3600_000L) {
                    Log.i("ClipRadar", YtDlp.update(this@ClipRadarApp))
                    s.lastYtDlpUpdate = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Log.w("ClipRadar", "yt-dlp: ${e.message}")
            }
        }
        Scheduler.apply(this)
    }

    /** Превью клипов: Coil умеет доставать кадр из видео. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
}
