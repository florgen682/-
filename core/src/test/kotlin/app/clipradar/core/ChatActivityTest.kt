package app.clipradar.core

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatActivityTest {
    @Test
    fun hypeWordsCountDouble() {
        assertEquals(2, ChatKeywords.weight("KEKW"))
        assertEquals(2, ChatKeywords.weight("ахахахаха что это"))
        assertEquals(2, ChatKeywords.weight("POGGERS"))
        assertEquals(2, ChatKeywords.weight("OMEGALUL"))
        assertEquals(2, ChatKeywords.weight("ну это жесть"))
        assertEquals(2, ChatKeywords.weight("я ору"))
        assertEquals(2, ChatKeywords.weight("Clip it!"))
        assertEquals(2, ChatKeywords.weight("имба"))
        assertEquals(1, ChatKeywords.weight("привет всем"))
        assertEquals(1, ChatKeywords.weight("какое оружие лучше"))
    }

    @Test
    fun englishChatAndEmojisCountDouble() {
        for (msg in listOf("LMAO", "hahahaha", "ICANT", "W", "WWWW", "OMEGALUL", "KEKL", "no way bro", "💀💀💀", "😂", "Sheesh", "PogU", "LMFAO what"))
            assertEquals(2, ChatKeywords.weight(msg), msg)
        for (msg in listOf("hello chat", "what game is this", "where is he from", "wow nice"))
            assertEquals(1, ChatKeywords.weight(msg), msg)
    }

    @Test
    fun bigChatNeedsSmallerJump() {
        val rule = BurstRule()
        // Огромный чат: обычно 300 за 10 секунд. Всплеск до 600 (×2) — это момент.
        val big = ChatSnapshot(weighted = 600, messages = 400, hypeMessages = 200, uniqueUsers = 350, baseline = 300.0, historyMs = 120_000)
        assertTrue(rule.isBurst(big))
        // Маленький чат: обычно 10, всплеск ×2 (20) — ещё не момент.
        val small = ChatSnapshot(weighted = 20, messages = 15, hypeMessages = 5, uniqueUsers = 12, baseline = 10.0, historyMs = 120_000)
        assertFalse(rule.isBurst(small))
    }

    @Test
    fun countsOnlyLast10Seconds() {
        val t = ChatActivityTracker()
        t.start(0)
        t.add(1_000, "a", "hi")
        t.add(15_000, "b", "hello")
        t.add(18_000, "c", "lol")
        val s = t.snapshot(20_000)
        assertEquals(2, s.messages)          // 15с и 18с попали в окно 10..20с
        assertEquals(3, s.weighted)          // lol считается за два
        assertEquals(1, s.hypeMessages)
        assertEquals(2, s.uniqueUsers)
    }

    @Test
    fun baselineIsAveragePer10Seconds() {
        val t = ChatActivityTracker()
        t.start(0)
        // 2 минуты по 1 сообщению каждые 2 секунды = 5 за 10 секунд.
        for (ms in 0 until 120_000 step 2_000) t.add(ms.toLong(), "u$ms", "msg")
        val s = t.snapshot(120_000)
        assertEquals(5.0, s.baseline, 0.3)
    }

    @Test
    fun burstDetectedWhenThreeTimesHigherAndMoreThan15() {
        val t = ChatActivityTracker()
        val rule = BurstRule()
        t.start(0)
        for (ms in 0 until 120_000 step 2_000) t.add(ms.toLong(), "u", "msg") // норма ~5 / 10с
        assertFalse(rule.isBurst(t.snapshot(120_000)))
        // Взрыв: 20 людей пишут KEKW за 5 секунд.
        for (i in 0 until 20) t.add(120_000L + i * 250, "user$i", "KEKW")
        val s = t.snapshot(125_000)
        assertTrue(s.weighted > 15)
        assertTrue(s.ratio >= 3.0, "ratio=${s.ratio}")
        assertTrue(rule.isBurst(s))
        assertEquals(21, s.uniqueUsers) // 20 новых + постоянный зритель "u"
    }

    @Test
    fun noBurstWhenChatIsAlwaysBusy() {
        val t = ChatActivityTracker()
        val rule = BurstRule()
        t.start(0)
        // Всегда 30 сообщений за 10 секунд — это норма, а не момент.
        for (ms in 0 until 200_000 step 333) t.add(ms.toLong(), "u$ms", "text")
        val s = t.snapshot(200_000)
        assertTrue(s.weighted > 15)
        assertFalse(rule.isBurst(s))
    }

    @Test
    fun noBurstDuringWarmup() {
        val t = ChatActivityTracker()
        t.start(0)
        for (i in 0 until 40) t.add(5_000L + i * 100, "u$i", "lol")
        assertFalse(BurstRule().isBurst(t.snapshot(10_000)))
    }

    @Test
    fun sensitivityChangesRule() {
        assertTrue(BurstRule.forSensitivity(2).minWeighted < BurstRule.forSensitivity(1).minWeighted)
        assertTrue(BurstRule.forSensitivity(0).multiplier > BurstRule.forSensitivity(1).multiplier)
    }

    @Test
    fun ircLineParsedToChatMessage() {
        val line = "@badge-info=;display-name=Some\\sGuy;tmi-sent-ts=1700000000000 :someguy!someguy@someguy.tmi.twitch.tv PRIVMSG #streamer :KEKW это жесть"
        val m = IrcParser.parseLine(line)!!
        assertEquals("PRIVMSG", m.command)
        assertEquals("#streamer", m.params[0])
        val chat = IrcParser.toChatLine(m)!!
        assertEquals("Some Guy", chat.user)
        assertEquals("KEKW это жесть", chat.text)
        assertEquals(1700000000000, chat.sentAtMs)
    }

    @Test
    fun ircPingAndMultiLineFrame() {
        val msgs = IrcParser.parseFrame("PING :tmi.twitch.tv\r\n:a!a@a.tmi.twitch.tv PRIVMSG #x :hi\r\n")
        assertEquals(2, msgs.size)
        assertEquals("PING", msgs[0].command)
        assertEquals("tmi.twitch.tv", msgs[0].trailing)
        assertEquals("a", IrcParser.toChatLine(msgs[1])!!.user)
    }
}
