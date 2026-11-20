package app.clipradar.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Как укладывать горизонтальное видео в вертикальный кадр 9:16. */
enum class ClipLayout(val title: String) {
    BLUR("Размытый фон (как в Shorts)"),
    BLACK("Чёрные поля"),
    CROP("Обрезать по центру (на весь экран)"),
}

/** Все настройки приложения. */
data class AppSettings(
    val autoCut: Boolean = true,
    val vodBeforeSec: Int = 20,
    val vodAfterSec: Int = 25,
    val liveBeforeSec: Int = 40,
    val liveAfterSec: Int = 15,
    val cooldownSec: Int = 90,
    /** 0 — низкая, 1 — обычная, 2 — высокая. */
    val chatSensitivity: Int = 1,
    val scoreThreshold: Int = 60,
    /** Максимальная высота скачиваемого видео: 480 / 720 / 1080. */
    val quality: Int = 720,
    val layout: ClipLayout = ClipLayout.BLUR,
    /** «Всегда на страже»: проверка стримеров каждые 2 минуты. */
    val guardMode: Boolean = false,
    val quietEnabled: Boolean = true,
    val quietStartMin: Int = 23 * 60,
    val quietEndMin: Int = 8 * 60,
)

/** Хранит настройки в памяти телефона (SharedPreferences) и сообщает об изменениях. */
class Settings private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current: AppSettings get() = _state.value

    private fun read(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            autoCut = prefs.getBoolean("autoCut", d.autoCut),
            vodBeforeSec = prefs.getInt("vodBefore", d.vodBeforeSec),
            vodAfterSec = prefs.getInt("vodAfter", d.vodAfterSec),
            liveBeforeSec = prefs.getInt("liveBefore", d.liveBeforeSec),
            liveAfterSec = prefs.getInt("liveAfter", d.liveAfterSec),
            cooldownSec = prefs.getInt("cooldown", d.cooldownSec),
            chatSensitivity = prefs.getInt("sensitivity", d.chatSensitivity),
            scoreThreshold = prefs.getInt("threshold", d.scoreThreshold),
            quality = prefs.getInt("quality", d.quality),
            layout = runCatching { ClipLayout.valueOf(prefs.getString("layout", d.layout.name)!!) }.getOrDefault(d.layout),
            guardMode = prefs.getBoolean("guard", d.guardMode),
            quietEnabled = prefs.getBoolean("quiet", d.quietEnabled),
            quietStartMin = prefs.getInt("quietStart", d.quietStartMin),
            quietEndMin = prefs.getInt("quietEnd", d.quietEndMin),
        )
    }

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val s = change(_state.value)
        prefs.edit()
            .putBoolean("autoCut", s.autoCut)
            .putInt("vodBefore", s.vodBeforeSec)
            .putInt("vodAfter", s.vodAfterSec)
            .putInt("liveBefore", s.liveBeforeSec)
            .putInt("liveAfter", s.liveAfterSec)
            .putInt("cooldown", s.cooldownSec)
            .putInt("sensitivity", s.chatSensitivity)
            .putInt("threshold", s.scoreThreshold)
            .putInt("quality", s.quality)
            .putString("layout", s.layout.name)
            .putBoolean("guard", s.guardMode)
            .putBoolean("quiet", s.quietEnabled)
            .putInt("quietStart", s.quietStartMin)
            .putInt("quietEnd", s.quietEndMin)
            .apply()
        _state.value = s
    }

    // Служебные значения (не настройки пользователя)
    var lastYtDlpUpdate: Long
        get() = prefs.getLong("ytdlpUpdated", 0)
        set(v) = prefs.edit().putLong("ytdlpUpdated", v).apply()

    var batteryAsked: Boolean
        get() = prefs.getBoolean("batteryAsked", false)
        set(v) = prefs.edit().putBoolean("batteryAsked", v).apply()

    /** Итоги стримов, которые ждут утра (режим «тихо ночью»). */
    @Synchronized
    fun addPendingSummary(text: String) {
        val list = pendingSummaries().toMutableList().apply { add(text) }
        prefs.edit().putString("pendingSummaries", list.joinToString(SEP)).apply()
    }

    fun pendingSummaries(): List<String> =
        prefs.getString("pendingSummaries", "")!!.split(SEP).filter { it.isNotBlank() }

    @Synchronized
    fun takePendingSummaries(): List<String> {
        val list = pendingSummaries()
        prefs.edit().remove("pendingSummaries").apply()
        return list
    }

    companion object {
        private const val SEP = "\u001E"

        @Volatile
        private var instance: Settings? = null

        fun get(context: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(context).also { instance = it }
        }
    }
}
