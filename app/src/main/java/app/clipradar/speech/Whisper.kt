package app.clipradar.speech

import android.content.Context
import app.clipradar.core.Word
import app.clipradar.live.Http
import app.clipradar.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Прямой доступ к whisper.cpp (C++), см. app/src/main/cpp/whisper_jni.cpp. */
object WhisperNative {
    val loaded: Boolean = try {
        System.loadLibrary("clipradar_whisper")
        true
    } catch (e: Throwable) {
        AppLog.e("Whisper", "Библиотека распознавания не загрузилась", e)
        false
    }

    @JvmStatic external fun initContext(modelPath: String): Long
    @JvmStatic external fun freeContext(ptr: Long)
    @JvmStatic external fun abort(ptr: Long)
    @JvmStatic external fun transcribe(ptr: Long, samples: FloatArray, language: String, threads: Int): ByteArray
    @JvmStatic external fun systemInfo(): String
}

/** Модели Whisper: tiny — быстрее, base — точнее. Скачиваются один раз (бесплатно, с Hugging Face). */
enum class WhisperModel(val fileName: String, val title: String, val sizeMb: Int) {
    TINY("ggml-tiny-q5_1.bin", "tiny — быстрая (31 МБ)", 31),
    BASE("ggml-base-q5_1.bin", "base — точнее (57 МБ)", 57),
}

data class Transcript(val language: String, val words: List<Word>)

object ModelManager {
    private const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    private val mutex = Mutex()

    fun file(context: Context, model: WhisperModel) = File(File(context.filesDir, "models").apply { mkdirs() }, model.fileName)

    fun isReady(context: Context, model: WhisperModel): Boolean = isValid(file(context, model))

    /** Модель whisper.cpp начинается с «волшебных» байт "lmgg". */
    private fun isValid(f: File): Boolean {
        if (!f.exists() || f.length() < 10L * 1024 * 1024) return false
        return f.inputStream().use { s ->
            val b = ByteArray(4)
            s.read(b) == 4 && b[0] == 0x6c.toByte() && b[1] == 0x6d.toByte() && b[2] == 0x67.toByte() && b[3] == 0x67.toByte()
        }
    }

    /** Скачивает модель, если её ещё нет. [onProgress] — проценты. */
    suspend fun ensure(context: Context, model: WhisperModel, onProgress: (Int) -> Unit = {}): File = mutex.withLock {
        val target = file(context, model)
        if (isValid(target)) return target
        withContext(Dispatchers.IO) {
            AppLog.i("Whisper", "Скачиваю модель ${model.fileName}")
            val tmp = File(target.parentFile, target.name + ".part")
            Http.client.newCall(Request.Builder().url(BASE_URL + model.fileName).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Модель не скачалась: HTTP ${r.code}")
                val body = r.body ?: throw IOException("Пустой ответ")
                val total = body.contentLength()
                var done = 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }
            if (!isValid(tmp)) {
                tmp.delete()
                throw IOException("Скачанная модель повреждена")
            }
            tmp.renameTo(target)
        }
        target
    }
}

/** Расшифровка речи на телефоне (офлайн). Одновременно — только одна. */
object Transcriber {
    private val mutex = Mutex()
    private var ctxPtr = 0L
    private var ctxModel: WhisperModel? = null

    /**
     * [pcm16k] — звук 16 кГц моно. [language] — "en", "ru"… или "auto".
     * Возвращает слова с таймкодами (секунды от начала переданного звука).
     */
    suspend fun transcribe(context: Context, model: WhisperModel, pcm16k: FloatArray, language: String): Transcript =
        mutex.withLock {
            if (!WhisperNative.loaded) throw IOException("Распознавание речи недоступно на этом телефоне")
            val path = ModelManager.ensure(context, model).absolutePath
            if (ctxPtr == 0L || ctxModel != model) {
                if (ctxPtr != 0L) WhisperNative.freeContext(ctxPtr)
                ctxPtr = withContext(Dispatchers.IO) { WhisperNative.initContext(path) }
                ctxModel = model
                if (ctxPtr == 0L) throw IOException("Не удалось загрузить модель Whisper")
            }
            val ptr = ctxPtr
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val started = System.currentTimeMillis()
            val bytes = suspendCancellableCoroutine<ByteArray> { cont ->
                thread(name = "whisper") {
                    try {
                        val r = WhisperNative.transcribe(ptr, pcm16k, language, threads)
                        if (cont.isActive) cont.resume(r)
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
                cont.invokeOnCancellation { WhisperNative.abort(ptr) }
            }
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val rc = json.optInt("rc", -1)
            if (rc != 0) throw IOException("Whisper вернул ошибку $rc")
            val arr = json.optJSONArray("words")
            val words = ArrayList<Word>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val t = o.optString("t").trim()
                    if (t.isEmpty()) continue
                    words += Word(t, o.optDouble("s"), o.optDouble("e"))
                }
            }
            AppLog.i(
                "Whisper",
                "Расшифровано ${pcm16k.size / 16000} с звука за ${(System.currentTimeMillis() - started) / 1000} с: " +
                    "${words.size} слов, язык ${json.optString("lang")}",
            )
            Transcript(json.optString("lang").ifBlank { language }, words)
        }
}
