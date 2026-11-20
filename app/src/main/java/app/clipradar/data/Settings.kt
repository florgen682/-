package app.clipradar.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Как укладывать горизонтальное видео в вертикальный кадр 9:16. Чёрных полей нет ни в одном режиме. */
enum class ClipLayout(val title: String) {
    FILL("Заполнить: на весь экран, кадр следует за лицом/действием"),
    BLUR("Размытый фон: сверху и снизу размытая копия"),
    STREAM("Стрим-раскладка: вебка сверху, игра снизу"),
    ;

    companion object {
        /** Старые режимы «чёрные поля» и «обрезать по центру» → «Заполнить». */
        fun parse(name: String?): ClipLayout = when (name) {
            "BLUR" -> BLUR
            "STREAM" -> STREAM
            else -> FILL
        }
    }
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
    val quality: Int = 1080,
    val layout: ClipLayout = ClipLayout.FILL,
    /** Расшифровывать речь (умные границы + субтитры). */
    val speechEnabled: Boolean = true,
    /** Модель Whisper: TINY или BASE. */
    val whisperModel: String = "TINY",
    /** Язык речи: "en", "ru" или "auto". */
    val speechLanguage: String = "en",
    val subtitles: Boolean = true,
    /** «Хук в начале»: первые 1.5–2 с — кусок пика. */
    val hookAtStart: Boolean = false,
    /** Показать текст-хук (например, WAIT FOR IT) первые 2 секунды. */
    val hookTextOnVideo: Boolean = false,
    /** Максимальная длина клипа, с (минимум всегда 12). */
    val maxClipSec: Int = 60,
    /** Необязательный ключ Claude API для подписей. */
    val aiApiKey: String = "",
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
            layout = ClipLayout.parse(prefs.getString("layout", d.layout.name)),
            speechEnabled = prefs.getBoolean("speech", d.speechEnabled),
            whisperModel = prefs.getString("whisperModel", d.whisperModel)!!,
            speechLanguage = prefs.getString("speechLang", d.speechLanguage)!!,
            subtitles = prefs.getBoolean("subtitles", d.subtitles),
            hookAtStart = prefs.getBoolean("hookAtStart", d.hookAtStart),
            hookTextOnVideo = prefs.getBoolean("hookText", d.hookTextOnVideo),
            maxClipSec = prefs.getInt("maxClip", d.maxClipSec),
            aiApiKey = prefs.getString("aiKey", d.aiApiKey)!!,
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
            .putBoolean("speech", s.speechEnabled)
            .putString("whisperModel", s.whisperModel)
            .putString("speechLang", s.speechLanguage)
            .putBoolean("subtitles", s.subtitles)
            .putBoolean("hookAtStart", s.hookAtStart)
            .putBoolean("hookText", s.hookTextOnVideo)
            .putInt("maxClip", s.maxClipSec)
            .putString("aiKey", s.aiApiKey)
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
