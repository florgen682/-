package app.clipradar.data

import app.clipradar.core.TimeRange
import app.clipradar.core.Word
import org.json.JSONArray
import org.json.JSONObject

/**
 * Подробности о моменте (хранятся в БД одним JSON): расшифровка, чат вокруг момента,
 * почему выбраны такие границы и готовые подписи для публикации.
 */
data class MomentDetails(
    val words: List<Word>? = null,
    val language: String? = null,
    /** Сообщения чата за каждую секунду исходника (только для эфиров). */
    val chatPerSec: List<Double>? = null,
    /** Обычный чат — сообщений в секунду. */
    val chatBaseline: Double? = null,
    val chatHype: Double? = null,
    /** Отрезок (во времени исходника), который обязательно должен войти — объединённые моменты. */
    val mustInclude: TimeRange? = null,
    /** Время по часам телефона, когда начинается файл-исходник (эфиры), мс. */
    val sourceStartWall: Long? = null,
    val streamer: String? = null,
    val channelUrl: String? = null,
    val game: String? = null,
    val startReason: String? = null,
    val endReason: String? = null,
    val layoutNote: String? = null,
    val titles: List<String> = emptyList(),
    val hookText: String? = null,
    val description: String? = null,
    val hashtags: List<String> = emptyList(),
    val captionsByAi: Boolean = false,
    val badFraction: Double? = null,
    val hook: TimeRange? = null,
) {
    fun toJson(): String = JSONObject().apply {
        words?.let { list ->
            put("words", JSONArray().apply { list.forEach { put(JSONArray().put(it.text).put(r2(it.start)).put(r2(it.end))) } })
        }
        language?.let { put("lang", it) }
        chatPerSec?.let { put("chat", JSONArray(it.map { v -> r2(v) })) }
        chatBaseline?.let { put("chatBase", it) }
        chatHype?.let { put("chatHype", it) }
        mustInclude?.let { put("must", JSONArray().put(it.startSec).put(it.endSec)) }
        hook?.let { put("hook", JSONArray().put(it.startSec).put(it.endSec)) }
        sourceStartWall?.let { put("srcWall", it) }
        streamer?.let { put("streamer", it) }
        channelUrl?.let { put("channelUrl", it) }
        game?.let { put("game", it) }
        startReason?.let { put("startReason", it) }
        endReason?.let { put("endReason", it) }
        layoutNote?.let { put("layoutNote", it) }
        put("titles", JSONArray(titles))
        hookText?.let { put("hookText", it) }
        description?.let { put("description", it) }
        put("hashtags", JSONArray(hashtags))
        put("ai", captionsByAi)
        badFraction?.let { put("bad", it) }
    }.toString()

    companion object {
        private fun r2(v: Double) = Math.round(v * 100) / 100.0

        fun parse(json: String?): MomentDetails {
            if (json.isNullOrBlank()) return MomentDetails()
            return try {
                val o = JSONObject(json)
                fun range(key: String) = o.optJSONArray(key)?.let { TimeRange(it.getDouble(0), it.getDouble(1)) }
                fun strings(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                MomentDetails(
                    words = o.optJSONArray("words")?.let { a ->
                        (0 until a.length()).map { i -> a.getJSONArray(i).let { w -> Word(w.getString(0), w.getDouble(1), w.getDouble(2)) } }
                    },
                    language = o.optString("lang").takeIf { it.isNotEmpty() },
                    chatPerSec = o.optJSONArray("chat")?.let { a -> (0 until a.length()).map { a.getDouble(it) } },
                    chatBaseline = if (o.has("chatBase")) o.getDouble("chatBase") else null,
                    chatHype = if (o.has("chatHype")) o.getDouble("chatHype") else null,
                    mustInclude = range("must"),
                    hook = range("hook"),
                    sourceStartWall = if (o.has("srcWall")) o.getLong("srcWall") else null,
                    streamer = o.optString("streamer").takeIf { it.isNotEmpty() },
                    channelUrl = o.optString("channelUrl").takeIf { it.isNotEmpty() },
                    game = o.optString("game").takeIf { it.isNotEmpty() },
                    startReason = o.optString("startReason").takeIf { it.isNotEmpty() },
                    endReason = o.optString("endReason").takeIf { it.isNotEmpty() },
                    layoutNote = o.optString("layoutNote").takeIf { it.isNotEmpty() },
                    titles = strings("titles"),
                    hookText = o.optString("hookText").takeIf { it.isNotEmpty() },
                    description = o.optString("description").takeIf { it.isNotEmpty() },
                    hashtags = strings("hashtags"),
                    captionsByAi = o.optBoolean("ai", false),
                    badFraction = if (o.has("bad")) o.getDouble("bad") else null,
                )
            } catch (e: Exception) {
                MomentDetails()
            }
        }
    }
}

/** Ключ для настроек конкретного стримера/канала. */
fun MomentEntity.channelKey(): String =
    if (isLive && channel != null) "twitch:${channel.lowercase()}" else "vod:${(channel ?: sourceUrl).lowercase()}"
