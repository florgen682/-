package app.clipradar.core

/**
 * Слова, которые означают «смешно/круто». Сообщение с ними считается за два.
 * Есть и русские, и английские слова + эмоуты Twitch и эмодзи — для западных стримеров.
 */
object ChatKeywords {
    /** Слово засчитывается, если сообщение содержит его как начало слова (pog → poggers). */
    private val prefixWords = listOf(
        // русские
        "лол", "кек", "жесть", "имба",
        // английские и эмоуты Twitch
        "lol", "kekw", "kekl", "lul", "pog", "clip", "lmao", "lmfao", "rofl", "icant",
        "omegalul", "sheesh", "noway", "insane", "wtf", "omg", "goated", "dead",
    )

    /** Эти части засчитываются где угодно внутри слова (ахахаха, OMEGALUL, hahaha). */
    private val anywhere = listOf("ахах", "хаха", "haha", "lmao", "lul", "kekw")

    /** Только целым словом: «ору» (иначе «оружие» тоже бы считалось), «W»-спам, «xdd». */
    private val exactWords = setOf("ору", "оруу", "орууу", "w", "ww", "www", "xd", "xdd", "ez", "holy")

    /** Фразы из нескольких слов. */
    private val phrases = listOf("no way", "oh my god", "let's go", "lets go")

    /** Эмодзи смеха и шока. */
    private val emojis = listOf("😂", "🤣", "💀", "😭", "🔥", "😱")

    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    fun isHype(text: String): Boolean {
        if (emojis.any { text.contains(it) }) return true
        val lower = text.lowercase()
        if (phrases.any { lower.contains(it) }) return true
        val tokens = lower.split(splitter).filter { it.isNotEmpty() }
        for (t in tokens) {
            if (t in exactWords || (t.startsWith("ору") && t.drop(3).all { it == 'у' })) return true
            if (t.length >= 2 && t.all { it == 'w' }) return true // WWWW
            if (prefixWords.any { t.startsWith(it) }) return true
            if (anywhere.any { t.contains(it) }) return true
        }
        return false
    }

    /** Вес сообщения: 2 — если есть «смешные» слова, иначе 1. */
    fun weight(text: String): Int = if (isHype(text)) 2 else 1
}

/** Снимок активности чата в текущий момент. */
data class ChatSnapshot(
    /** Сумма весов сообщений за последние 10 секунд. */
    val weighted: Int,
    /** Просто количество сообщений за последние 10 секунд. */
    val messages: Int,
    /** Сколько из них со «смешными» словами. */
    val hypeMessages: Int,
    /** Сколько разных людей писали за последние 10 секунд. */
    val uniqueUsers: Int,
    /** «Обычная» активность: средний вес за 10 секунд раньше (за последние 5 минут). */
    val baseline: Double,
    /** Сколько миллисекунд истории уже накоплено. */
    val historyMs: Long,
) {
    /** Во сколько раз сейчас активнее, чем обычно. */
    val ratio: Double get() = weighted / maxOf(baseline, 1.0)
    val hypeShare: Double get() = if (messages == 0) 0.0 else hypeMessages.toDouble() / messages
}

/**
 * Правило «это момент!»: активность в [multiplier] раз выше обычной
 * и больше [minWeighted] сообщений за окно.
 */
data class BurstRule(
    val multiplier: Double = 3.0,
    val minWeighted: Int = 15,
    val warmupMs: Long = 60_000,
) {
    /**
     * Во сколько раз чат должен «взорваться». У огромных чатов (сотни сообщений за 10 секунд,
     * как у крупных западных стримеров) всплеск в 2 раза — это уже очень много, поэтому планка ниже.
     */
    fun effectiveMultiplier(baseline: Double): Double =
        if (baseline >= BIG_CHAT_BASELINE) maxOf(1.5, multiplier * 0.6) else multiplier

    fun isBurst(s: ChatSnapshot): Boolean =
        s.historyMs >= warmupMs &&
            s.weighted > minWeighted &&
            s.weighted >= effectiveMultiplier(s.baseline) * maxOf(s.baseline, 1.0)

    companion object {
        /** С какого уровня (взвешенных сообщений за 10 секунд) чат считается «огромным». */
        const val BIG_CHAT_BASELINE = 100.0

        /** Чувствительность чата: 0 — низкая, 1 — обычная, 2 — высокая. */
        fun forSensitivity(level: Int): BurstRule = when (level) {
            0 -> BurstRule(multiplier = 4.0, minWeighted = 25)
            2 -> BurstRule(multiplier = 2.0, minWeighted = 8)
            else -> BurstRule(multiplier = 3.0, minWeighted = 15)
        }
    }
}

/**
 * Считает сообщения чата в скользящем окне (по умолчанию 10 секунд)
 * и «обычный» уровень за последние 5 минут.
 *
 * Время передаётся снаружи (в миллисекундах), поэтому класс легко тестировать.
 */
class ChatActivityTracker(
    private val windowMs: Long = 10_000,
    private val baselineMs: Long = 300_000,
) {
    private class Msg(val t: Long, val weight: Int, val user: String, val hype: Boolean)

    private val msgs = ArrayDeque<Msg>()
    private var startedAt: Long? = null

    /** Отметить начало наблюдения (иначе отсчёт начнётся с первого сообщения). */
    @Synchronized
    fun start(nowMs: Long) {
        if (startedAt == null) startedAt = nowMs
    }

    @Synchronized
    fun add(timeMs: Long, user: String, text: String) {
        if (startedAt == null) startedAt = timeMs
        val hype = ChatKeywords.isHype(text)
        msgs.addLast(Msg(timeMs, if (hype) 2 else 1, user.lowercase(), hype))
    }

    @Synchronized
    fun snapshot(nowMs: Long): ChatSnapshot {
        // Удаляем сообщения старше 5 минут.
        while (msgs.isNotEmpty() && msgs.first().t < nowMs - baselineMs) msgs.removeFirst()

        val windowStart = nowMs - windowMs
        var weighted = 0
        var count = 0
        var hype = 0
        val users = HashSet<String>()
        var olderWeight = 0L
        for (m in msgs) {
            if (m.t > nowMs) continue
            if (m.t >= windowStart) {
                weighted += m.weight
                count++
                if (m.hype) hype++
                users += m.user
            } else {
                olderWeight += m.weight
            }
        }
        val history = nowMs - (startedAt ?: nowMs)
        // Сколько времени «до окна» у нас есть для расчёта нормы.
        val span = minOf(baselineMs - windowMs, history - windowMs)
        val baseline = if (span > 0) olderWeight.toDouble() * windowMs / span else 0.0
        return ChatSnapshot(weighted, count, hype, users.size, baseline, history)
    }

    /**
     * Взвешенное число сообщений за каждую секунду в отрезке [fromMs, toMs)
     * (для поиска начала и конца реакции чата). Хранится только последние 5 минут.
     */
    @Synchronized
    fun perSecond(fromMs: Long, toMs: Long): List<Double> {
        val n = ((toMs - fromMs) / 1000).toInt().coerceAtLeast(0)
        val out = DoubleArray(n)
        for (m in msgs) {
            if (m.t < fromMs || m.t >= toMs) continue
            val i = ((m.t - fromMs) / 1000).toInt()
            if (i in 0 until n) out[i] += m.weight.toDouble()
        }
        return out.toList()
    }

    @Synchronized
    fun reset() {
        msgs.clear()
        startedAt = null
    }
}
