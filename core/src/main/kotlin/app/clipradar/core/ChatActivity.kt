package app.clipradar.core

/** Слова, которые означают «смешно/круто». Сообщение с ними считается за два. */
object ChatKeywords {
    /** Слово засчитывается, если сообщение содержит его как начало слова (pog → poggers). */
    private val prefixWords = listOf(
        "lol", "kekw", "lul", "pog", "clip", "лол", "кек", "жесть", "имба",
    )

    /** Эти части засчитываются где угодно внутри слова (ахахаха, OMEGALUL, хахах). */
    private val anywhere = listOf("ахах", "хаха", "lul", "kekw")

    /** «ору» — только целым словом (иначе «оружие» тоже бы считалось). */
    private val exactWords = setOf("ору", "орууу", "оруу")

    private val splitter = Regex("[^\\p{L}\\p{N}]+")

    fun isHype(text: String): Boolean {
        val lower = text.lowercase()
        val tokens = lower.split(splitter).filter { it.isNotEmpty() }
        for (t in tokens) {
            if (t in exactWords || (t.startsWith("ору") && t.drop(3).all { it == 'у' })) return true
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
    fun isBurst(s: ChatSnapshot): Boolean =
        s.historyMs >= warmupMs &&
            s.weighted > minWeighted &&
            s.weighted >= multiplier * maxOf(s.baseline, 1.0)

    companion object {
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

    @Synchronized
    fun reset() {
        msgs.clear()
        startedAt = null
    }
}
