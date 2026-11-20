package app.clipradar.core

import kotlin.math.max
import kotlin.math.min

/** Один кусок субтитров (время — от начала клипа). */
data class SubtitleCue(val start: Double, val end: Double, val text: String)

/** Субтитры из пословной расшифровки: крупно, по 2–3 слова. */
object Subtitles {
    private val junk = Regex("^[\\[(].*[\\])]$|^[♪♫*]+$")

    fun cues(
        words: List<Word>,
        clipStart: Double,
        clipEnd: Double,
        maxWords: Int = 3,
        maxChars: Int = 16,
    ): List<SubtitleCue> {
        val inClip = words
            .filter { it.end > clipStart && it.start < clipEnd }
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() && !junk.matches(it.text) }
        val groups = ArrayList<MutableList<Word>>()
        for (w in inClip) {
            val g = groups.lastOrNull()
            val joined = g?.joinToString(" ") { it.text }?.length ?: 0
            val startNew = g == null ||
                g.size >= maxWords ||
                joined + 1 + w.text.length > maxChars ||
                w.start - g.last().end > 0.5 ||
                g.last().text.endsWith(".") || g.last().text.endsWith("?") || g.last().text.endsWith("!")
            if (startNew) groups += mutableListOf(w) else g!!.add(w)
        }
        val out = ArrayList<SubtitleCue>()
        for ((i, g) in groups.withIndex()) {
            val s = max(g.first().start, clipStart) - clipStart
            val nextStart = groups.getOrNull(i + 1)?.first()?.start?.minus(clipStart)
            var e = min(g.last().end, clipEnd) - clipStart + 0.25
            if (nextStart != null) e = min(e, nextStart)
            e = min(e, clipEnd - clipStart)
            if (e - s < 0.15) continue
            out += SubtitleCue(s, e, g.joinToString(" ") { it.text }.trimEnd(',', ';').uppercase())
        }
        return out
    }
}

/** Тексты для публикации клипа. */
data class PublishTexts(
    val titles: List<String>,
    val hook: String,
    val description: String,
    val hashtags: List<String>,
)

/** Какая это была реакция — от неё зависят эмодзи и слова в подписях. */
enum class MomentMood { LAUGH, SHOCK, HYPE }

/**
 * Подписи без платных API: шаблоны + ключевые слова из расшифровки.
 * Все названия — на английском, до 60 символов, с одним эмодзи.
 */
object CaptionWriter {
    private val stop = setOf(
        "the", "a", "an", "and", "or", "but", "so", "to", "of", "in", "on", "at", "for", "with", "is", "are", "was",
        "were", "be", "been", "it", "its", "this", "that", "i", "you", "he", "she", "we", "they", "me", "my", "your",
        "his", "her", "our", "their", "what", "just", "like", "yeah", "okay", "oh", "um", "uh", "dont", "do", "did",
        "not", "no", "yes", "can", "get", "got", "go", "going", "gonna", "know", "right", "there", "here", "then",
        "now", "all", "have", "has", "had", "im", "its", "thats", "bro", "dude", "man", "guys", "chat", "really",
        "very", "about", "from", "out", "up", "down", "if", "when", "how", "why", "who", "will", "would", "could",
        "should", "one", "some", "them", "him", "us", "too", "also", "because", "lol", "haha",
        "и", "в", "на", "не", "что", "это", "я", "ты", "он", "она", "мы", "они", "а", "но", "да", "нет", "ну", "вот",
        "так", "как", "там", "тут", "же", "бы", "по", "за", "с", "к", "у", "из", "то", "всё", "все", "уже", "еще", "ещё",
    )
    private val laugh = Regex("laugh|haha|ahah|lol|lmao|хаха|ахах|ору|kek|lul|😂|🤣|💀", RegexOption.IGNORE_CASE)
    private val shock = Regex("no way|what|oh my god|omg|wtf|holy|scream|жесть|что|😱|😳", RegexOption.IGNORE_CASE)

    fun mood(words: List<Word>, chatHype: Double? = null): MomentMood {
        val text = words.joinToString(" ") { it.text }
        val l = laugh.findAll(text).count() + if ((chatHype ?: 0.0) > 0.3) 2 else 0
        val s = shock.findAll(text).count()
        return when {
            l >= 2 && l >= s -> MomentMood.LAUGH
            s >= 1 -> MomentMood.SHOCK
            l >= 1 -> MomentMood.LAUGH
            else -> MomentMood.HYPE
        }
    }

    /** Самые «значимые» слова расшифровки (часто повторяются, не служебные). */
    fun keywords(words: List<Word>, max: Int = 3): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (w in SpeechText.speech(words)) {
            val t = w.text.lowercase().trim().trim { !it.isLetterOrDigit() }
            if (t.length < 4 || t in stop || t.all { it.isDigit() }) continue
            counts[t] = (counts[t] ?: 0) + 1
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
            .take(max).map { it.key }
    }

    fun generate(
        streamer: String?,
        channelUrl: String?,
        game: String?,
        words: List<Word>,
        platform: String,
        chatHype: Double? = null,
    ): PublishTexts {
        val name = streamer?.takeIf { it.isNotBlank() } ?: "Streamer"
        val m = mood(words, chatHype)
        val kw = keywords(words).map { it.replaceFirstChar(Char::uppercase) }
        val emoji = when (m) {
            MomentMood.LAUGH -> "😂"
            MomentMood.SHOCK -> "😱"
            MomentMood.HYPE -> "🔥"
        }
        val g = game?.takeIf { it.isNotBlank() }
        val k = kw.firstOrNull()
        val candidates = buildList {
            when (m) {
                MomentMood.LAUGH -> {
                    add("$name Couldn't Stop Laughing $emoji")
                    k?.let { add("$name Lost It Over $it $emoji") }
                    g?.let { add("Funniest $it Moment Ever $emoji") }
                    add("Chat Was Dying At This $emoji")
                }
                MomentMood.SHOCK -> {
                    add("$name Did NOT Expect This $emoji")
                    k?.let { add("Wait… $it?! $name Was Shocked $emoji") }
                    g?.let { add("This $it Moment Is Unreal $emoji") }
                    add("Nobody Saw This Coming $emoji")
                }
                MomentMood.HYPE -> {
                    add("$name Went Crazy Here $emoji")
                    g?.let { add("Insane $it Moment From $name $emoji") }
                    k?.let { add("$name and the $it Moment $emoji") }
                    add("Chat Exploded After This $emoji")
                }
            }
            add("Wait For The End… $name $emoji")
        }
        val titles = candidates.map { fit(it, 60) }.distinct().take(3)
        val hook = when (m) {
            MomentMood.LAUGH -> "WAIT FOR IT"
            MomentMood.SHOCK -> "NOBODY EXPECTED THIS"
            MomentMood.HYPE -> "WATCH THIS"
        }
        val tags = buildList {
            add(tag(name))
            g?.let { add(tag(it)) }
            add(if (platform == "twitch") "#twitch" else "#youtube")
            add("#shorts")
            add(if (m == MomentMood.LAUGH) "#funny" else "#clips")
        }.filter { it.length > 1 }.distinct().take(5)
        val description = buildString {
            append(titles.first()).append("\n\n")
            append("🎥 Clip from $name")
            if (g != null) append(" playing $g")
            append(".")
            if (channelUrl != null) append("\n👉 Watch $name: ").append(channelUrl)
            append("\nAll credit goes to $name.\n\n")
            append(tags.joinToString(" "))
        }
        return PublishTexts(titles, hook, description, tags)
    }

    private fun tag(s: String) = "#" + s.filter { it.isLetterOrDigit() }.lowercase()

    /** Укладываем в [limit] символов, сохраняя эмодзи в конце. */
    fun fit(title: String, limit: Int): String {
        if (title.length <= limit) return title
        val emoji = title.substringAfterLast(' ')
        val body = title.removeSuffix(emoji).trim()
        val room = limit - emoji.length - 2
        val cut = body.take(room).substringBeforeLast(' ').trimEnd(',', '.', '…')
        return "$cut… $emoji"
    }
}
