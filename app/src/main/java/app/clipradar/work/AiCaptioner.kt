package app.clipradar.work

import app.clipradar.core.CaptionWriter
import app.clipradar.core.PublishTexts
import app.clipradar.util.AppLog
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaFallbacksParam
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Необязательно: подписи пишет ИИ (Claude) по расшифровке. Нужен ключ API в настройках.
 * Любая ошибка → возвращаем null, и приложение использует шаблоны.
 */
object AiCaptioner {
    private const val TAG = "AiCaptioner"
    private const val MODEL = "claude-opus-5-5"

    private val schema: Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "titles" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "hook" to mapOf("type" to "string"),
            "description" to mapOf("type" to "string"),
            "hashtags" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
        ),
        "required" to listOf("titles", "hook", "description", "hashtags"),
        "additionalProperties" to false,
    )

    suspend fun generate(
        apiKey: String,
        transcript: String,
        streamer: String?,
        channelUrl: String?,
        game: String?,
        platform: String,
    ): PublishTexts? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || transcript.isBlank()) return@withContext null
        val client = AnthropicOkHttpClient.builder().apiKey(apiKey.trim()).build()
        try {
            val format = BetaJsonOutputFormat.builder()
                .schema(
                    BetaJsonOutputFormat.Schema.builder()
                        .apply { schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) } }
                        .build(),
                )
                .build()
            val prompt = buildString {
                append("Streamer: ").append(streamer ?: "unknown").append('\n')
                append("Platform: ").append(platform).append('\n')
                if (game != null) append("Game: ").append(game).append('\n')
                if (channelUrl != null) append("Channel link: ").append(channelUrl).append('\n')
                append("\nTranscript of the clip:\n").append(transcript.take(4000))
            }
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(16000L)
                // Если модель откажется, сервер сам повторит запрос на запасной модели.
                .addBeta("server-side-fallback-2026-07-01")
                .fallbacks(BetaFallbacksParam.ofDefault())
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).format(format).build())
                .system(
                    "You write publishing texts for a short vertical gaming/stream clip (YouTube Shorts, TikTok). " +
                        "Write in English. titles: exactly 3 options, each at most 60 characters, building intrigue, " +
                        "mentioning the streamer or the game, each with exactly one emoji at the end. " +
                        "hook: 2 to 4 words in capital letters to show on screen during the first 2 seconds. " +
                        "description: 2-3 short lines that credit the streamer by name, include the channel link if given, " +
                        "and end with the hashtags. hashtags: 3 to 5 hashtags starting with #, lowercase, no spaces. " +
                        "Base everything on what actually happens in the transcript.",
                )
                .addUserMessage(prompt)
                .build()
            val msg = client.beta().messages().create(params)
            if (msg.stopReason().orElse(null) == BetaStopReason.REFUSAL) {
                AppLog.w(TAG, "ИИ отказался писать подписи — использую шаблоны")
                return@withContext null
            }
            val text = msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
            val o = JSONObject(text)
            val titles = (0 until o.getJSONArray("titles").length())
                .map { CaptionWriter.fit(o.getJSONArray("titles").getString(it).trim(), 60) }
                .filter { it.isNotBlank() }
                .take(3)
            val hashtags = (0 until o.getJSONArray("hashtags").length())
                .map { o.getJSONArray("hashtags").getString(it).trim() }
                .map { if (it.startsWith("#")) it else "#$it" }
                .take(5)
            val hook = o.getString("hook").trim().split(Regex("\\s+")).take(4).joinToString(" ").uppercase()
            if (titles.size < 3 || hook.isBlank()) return@withContext null
            AppLog.i(TAG, "Подписи написал ИИ")
            PublishTexts(titles, hook, o.getString("description").trim(), hashtags)
        } catch (e: Exception) {
            AppLog.w(TAG, "ИИ-подписи не получились, использую шаблоны", e)
            null
        } finally {
            runCatching { client.close() }
        }
    }
}
