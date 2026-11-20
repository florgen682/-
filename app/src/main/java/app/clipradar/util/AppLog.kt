package app.clipradar.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Журнал приложения: все важные события и ошибки пишутся в файл внутри приложения.
 * Его можно открыть на экране «Журнал» и скопировать, чтобы отправить разработчику.
 */
object AppLog {
    private const val MAX_BYTES = 512 * 1024
    @Volatile private var file: File? = null
    private val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.US)

    fun init(context: Context) {
        file = File(context.filesDir, "journal.log")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    @Synchronized
    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        when (level) {
            "E" -> Log.e(tag, msg, t)
            "W" -> Log.w(tag, msg, t)
            else -> Log.i(tag, msg)
        }
        val f = file ?: return
        try {
            val line = buildString {
                append(time.format(Date())).append(' ').append(level).append(' ').append(tag).append(": ").append(msg)
                if (t != null) {
                    append(" — ").append(t.javaClass.simpleName).append(": ").append(t.message ?: "")
                    t.stackTrace.take(4).forEach { append("\n      at ").append(it) }
                }
                append('\n')
            }
            f.appendText(line)
            if (f.length() > MAX_BYTES) {
                // Оставляем вторую половину журнала.
                val text = f.readText()
                f.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
        } catch (_: Exception) {
        }
    }

    fun read(): String = try {
        file?.takeIf { it.exists() }?.readText() ?: ""
    } catch (e: Exception) {
        ""
    }

    @Synchronized
    fun clear() {
        file?.delete()
    }
}
