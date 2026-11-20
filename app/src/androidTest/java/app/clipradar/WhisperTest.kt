package app.clipradar

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.clipradar.media.AudioDecoder
import app.clipradar.speech.Transcriber
import app.clipradar.speech.WhisperModel
import app.clipradar.speech.WhisperNative
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** whisper.cpp на устройстве: расшифровка образца речи с таймкодами каждого слова. */
@RunWith(AndroidJUnit4::class)
class WhisperTest {
    @Test
    fun transcribesSpeechWithWordTimestamps() = runBlocking {
        assertTrue("библиотека распознавания не загрузилась", WhisperNative.loaded)
        TestFiles.report("whisper.txt", "system: ${WhisperNative.systemInfo()}")
        val scan = AudioDecoder.scan(TestFiles.asset("jfk.wav"), want16k = true)
        val pcm = scan.pcm16k!!
        assertTrue("звук не декодировался", pcm.size > 16000 * 10)
        val started = System.currentTimeMillis()
        val t = Transcriber.transcribe(TestFiles.ctx, WhisperModel.TINY, pcm, "en")
        val text = t.words.joinToString(" ") { it.text }
        TestFiles.report("whisper.txt", "за ${(System.currentTimeMillis() - started) / 1000} с: $text")
        t.words.forEach { TestFiles.report("whisper.txt", "  %.2f–%.2f  %s".format(it.start, it.end, it.text)) }
        assertTrue("ожидалась фраза про страну: $text", text.lowercase().contains("country"))
        assertTrue("таймкоды должны идти по порядку", t.words.zipWithNext().all { (a, b) -> b.start >= a.start - 0.05 })
        assertTrue("таймкоды внутри записи", t.words.all { it.start >= 0 && it.end <= 12.0 })
    }
}
