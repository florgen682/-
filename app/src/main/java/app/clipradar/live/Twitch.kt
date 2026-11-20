package app.clipradar.live

import android.content.Context
import app.clipradar.util.AppLog
import app.clipradar.core.ChatLine
import app.clipradar.core.IrcParser
import app.clipradar.core.Sources
import app.clipradar.media.YtDlp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Состояние канала: в эфире или нет. */
data class LiveInfo(
    val login: String,
    val displayName: String,
    val live: Boolean,
    val streamId: String?,
    val title: String?,
    val startedAt: Long?,
    val game: String? = null,
)

/** Проверка, идёт ли эфир. Сначала быстрый запрос к Twitch, если не вышло — через yt-dlp. */
object TwitchApi {
    private const val TAG = "TwitchApi"
    // Публичный идентификатор веб-сайта Twitch (его же использует yt-dlp).
    private const val CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko"

    suspend fun check(context: Context, logins: List<String>): Map<String, LiveInfo> {
        if (logins.isEmpty()) return emptyMap()
        val fast = runCatching { checkGql(logins) }
            .onFailure { AppLog.w(TAG, "GQL не ответил: ${it.message}") }
            .getOrNull()
        if (fast != null && fast.size == logins.size) return fast
        // Запасной путь — yt-dlp (медленнее, но надёжно).
        val result = HashMap<String, LiveInfo>(fast ?: emptyMap())
        for (login in logins) {
            if (login in result) continue
            result[login] = checkWithYtDlp(context, login) ?: continue
        }
        return result
    }

    private suspend fun checkGql(logins: List<String>): Map<String, LiveInfo> = withContext(Dispatchers.IO) {
        val list = JSONArray(logins).toString()
        val query = "query { users(logins: $list) { login displayName stream { id title createdAt game { name } } } }"
        val body = JSONObject().put("query", query).toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("https://gql.twitch.tv/gql")
            .header("Client-ID", CLIENT_ID)
            .post(body)
            .build()
        Http.client.newCall(req).execute().use { resp: Response ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val json = JSONObject(resp.body!!.string())
            val users = json.optJSONObject("data")?.optJSONArray("users") ?: error("нет данных: ${json.toString().take(200)}")
            val out = HashMap<String, LiveInfo>()
            for (i in 0 until users.length()) {
                val u = users.optJSONObject(i) ?: continue
                val login = u.optString("login").lowercase()
                val stream = u.optJSONObject("stream")
                out[login] = LiveInfo(
                    login = login,
                    displayName = u.optString("displayName", login),
                    live = stream != null,
                    streamId = stream?.optString("id"),
                    title = stream?.optString("title"),
                    startedAt = stream?.optString("createdAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
                    game = stream?.optJSONObject("game")?.optString("name")?.takeIf { it.isNotBlank() },
                )
            }
            // Канал, которого нет на Twitch, просто отсутствует в ответе — считаем «не в эфире».
            for (l in logins) if (l !in out) out[l] = LiveInfo(l, l, false, null, null, null)
            out
        }
    }

    private suspend fun checkWithYtDlp(context: Context, login: String): LiveInfo? = try {
        val meta = YtDlp.info(context, Sources.twitchChannelUrl(login))
        LiveInfo(login, meta.uploader ?: login, meta.isLive, meta.id, meta.title, null)
    } catch (e: Exception) {
        val msg = e.message?.lowercase() ?: ""
        if ("не в эфире" in msg || "not currently live" in msg || "offline" in msg) {
            LiveInfo(login, login, false, null, null, null)
        } else {
            AppLog.w(TAG, "Не удалось проверить $login", e)
            null
        }
    }
}

/**
 * Анонимное подключение к чату Twitch (без аккаунта) через WebSocket.
 * Сам переподключается, если связь оборвалась.
 */
class TwitchChat(
    private val channel: String,
    private val onMessage: (ChatLine) -> Unit,
) {
    @Volatile var connected = false
        private set

    suspend fun run() {
        var backoff = 2_000L
        while (coroutineContext.isActive) {
            val closed = CompletableDeferred<Unit>()
            val ws = Http.client.newWebSocket(
                Request.Builder().url("wss://irc-ws.chat.twitch.tv:443").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
                        webSocket.send("PASS SCHMOOPIIE")
                        webSocket.send("NICK justinfan12345")
                        webSocket.send("JOIN #$channel")
                        connected = true
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        for (m in IrcParser.parseFrame(text)) {
                            when (m.command) {
                                "PING" -> webSocket.send("PONG :${m.trailing ?: "tmi.twitch.tv"}")
                                "RECONNECT" -> webSocket.close(1000, "reconnect")
                                "PRIVMSG" -> IrcParser.toChatLine(m)?.let(onMessage)
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        connected = false
                        closed.complete(Unit)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        connected = false
                        AppLog.w("TwitchChat", "Чат отключился: ${t.message}")
                        closed.complete(Unit)
                    }
                },
            )
            val startedAt = System.currentTimeMillis()
            try {
                closed.await()
            } finally {
                ws.cancel()
                connected = false
            }
            // Если связь продержалась больше минуты — начинаем паузы заново с 2 секунд.
            if (System.currentTimeMillis() - startedAt > 60_000) backoff = 2_000L
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }
}
