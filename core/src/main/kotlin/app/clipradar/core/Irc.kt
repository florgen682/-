package app.clipradar.core

/** Сообщение протокола IRC, на котором работает чат Twitch. */
data class IrcMessage(
    val tags: Map<String, String>,
    val prefix: String?,
    val command: String,
    val params: List<String>,
) {
    /** Ник автора (из prefix вида nick!nick@nick.tmi.twitch.tv). */
    val nick: String? get() = prefix?.substringBefore('!')?.takeIf { it.isNotEmpty() }
    val trailing: String? get() = params.lastOrNull()
}

data class ChatLine(val user: String, val text: String, val sentAtMs: Long?)

object IrcParser {
    /** В одном сообщении WebSocket может прийти несколько строк. */
    fun parseFrame(frame: String): List<IrcMessage> =
        frame.split("\r\n", "\n").mapNotNull { if (it.isBlank()) null else parseLine(it) }

    fun parseLine(input: String): IrcMessage? {
        var rest = input.trimEnd('\r', '\n')
        if (rest.isEmpty()) return null
        var tags = emptyMap<String, String>()
        if (rest.startsWith("@")) {
            val sp = rest.indexOf(' ')
            if (sp < 0) return null
            tags = rest.substring(1, sp).split(';').associate {
                val k = it.substringBefore('=')
                val v = if (it.contains('=')) it.substringAfter('=') else ""
                k to unescapeTag(v)
            }
            rest = rest.substring(sp + 1).trimStart()
        }
        var prefix: String? = null
        if (rest.startsWith(":")) {
            val sp = rest.indexOf(' ')
            if (sp < 0) return null
            prefix = rest.substring(1, sp)
            rest = rest.substring(sp + 1).trimStart()
        }
        val params = ArrayList<String>()
        val command: String
        val sp = rest.indexOf(' ')
        if (sp < 0) {
            command = rest
        } else {
            command = rest.substring(0, sp)
            var p = rest.substring(sp + 1)
            while (p.isNotEmpty()) {
                if (p.startsWith(":")) {
                    params += p.substring(1)
                    break
                }
                val next = p.indexOf(' ')
                if (next < 0) {
                    params += p
                    break
                }
                params += p.substring(0, next)
                p = p.substring(next + 1)
            }
        }
        if (command.isEmpty()) return null
        return IrcMessage(tags, prefix, command.uppercase(), params)
    }

    /** Если это обычное сообщение в чат — возвращает автора и текст. */
    fun toChatLine(m: IrcMessage): ChatLine? {
        if (m.command != "PRIVMSG") return null
        val text = m.trailing ?: return null
        val user = m.tags["display-name"]?.takeIf { it.isNotBlank() } ?: m.nick ?: return null
        return ChatLine(user, text, m.tags["tmi-sent-ts"]?.toLongOrNull())
    }

    private fun unescapeTag(v: String): String =
        v.replace("\\s", " ").replace("\\:", ";").replace("\\\\", "\\")
}
