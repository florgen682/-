package app.clipradar.core

import java.time.LocalDateTime
import java.time.LocalTime

enum class SourceKind { TWITCH_CHANNEL, TWITCH_VOD, YOUTUBE, OTHER, INVALID }

/** Разбор того, что пользователь вставил в поле: ссылка или просто ник. */
object Sources {
    private val nickRegex = Regex("^[A-Za-z0-9_]{2,25}$")
    private val reservedPaths = setOf(
        "videos", "directory", "settings", "downloads", "jobs", "p", "search", "subscriptions",
        "inventory", "wallet", "friends", "messages", "turbo", "prime", "store", "login", "signup",
    )

    /** Ник Twitch-канала из ссылки или ника, иначе null. */
    fun twitchChannel(input: String): String? {
        var s = input.trim().removePrefix("@")
        if (s.isEmpty()) return null
        if (!s.contains('/') && !s.contains('.')) {
            return if (nickRegex.matches(s)) s.lowercase() else null
        }
        s = s.substringBefore('?').substringBefore('#')
        val noScheme = s.replaceFirst(Regex("^[a-zA-Z]+://"), "")
        val host = noScheme.substringBefore('/').lowercase()
        if (!(host == "twitch.tv" || host.endsWith(".twitch.tv"))) return null
        val parts = noScheme.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
        val nick = parts.firstOrNull() ?: return null
        if (nick.lowercase() in reservedPaths) return null
        // twitch.tv/nick/clip/... или /videos — это не канал
        if (parts.size > 1 && parts[1].lowercase() in setOf("clip", "video", "v")) return null
        return if (nickRegex.matches(nick)) nick.lowercase() else null
    }

    fun kind(input: String): SourceKind {
        val s = input.trim()
        if (s.isEmpty()) return SourceKind.INVALID
        val lower = s.lowercase()
        if (lower.contains("twitch.tv/videos/") || Regex("twitch\\.tv/[^/]+/v(ideo)?/\\d+").containsMatchIn(lower)) {
            return SourceKind.TWITCH_VOD
        }
        if (twitchChannel(s) != null) return SourceKind.TWITCH_CHANNEL
        if (lower.contains("youtube.com/") || lower.contains("youtu.be/")) return SourceKind.YOUTUBE
        if (lower.startsWith("http://") || lower.startsWith("https://")) return SourceKind.OTHER
        return SourceKind.INVALID
    }

    fun twitchChannelUrl(nick: String) = "https://www.twitch.tv/${nick.lowercase()}"

    /** Достаёт ссылку из текста, которым «поделились» из другого приложения. */
    fun extractUrl(text: String): String? =
        Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')
}

object TimeFormat {
    /** 3723 → "1:02:03", 75 → "1:15". */
    fun duration(totalSec: Double): String {
        val t = maxOf(0L, totalSec.toLong())
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}

/** Режим «тихо ночью». Время — в минутах от полуночи (23:00 = 1380). */
object QuietHours {
    fun isQuiet(minuteOfDay: Int, startMin: Int, endMin: Int): Boolean = when {
        startMin == endMin -> false
        startMin < endMin -> minuteOfDay in startMin until endMin
        else -> minuteOfDay >= startMin || minuteOfDay < endMin // через полночь
    }

    fun isQuiet(now: LocalDateTime, startMin: Int, endMin: Int): Boolean =
        isQuiet(now.hour * 60 + now.minute, startMin, endMin)

    /** Когда закончится «тихое» время (ближайшее будущее время endMin). */
    fun nextEnd(now: LocalDateTime, endMin: Int): LocalDateTime {
        val endTime = LocalTime.of(endMin / 60 % 24, endMin % 60)
        var candidate = now.toLocalDate().atTime(endTime)
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate
    }
}
