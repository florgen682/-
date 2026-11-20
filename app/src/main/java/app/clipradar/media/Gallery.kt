package app.clipradar.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** Сохранение клипов в галерею и всё, что связано с местом в памяти. */
object Gallery {
    const val FOLDER = "ClipRadar"
    const val MIN_FREE_BYTES = 1024L * 1024 * 1024 // 1 ГБ

    /** Копирует готовый клип в Movies/ClipRadar, чтобы он появился в галерее. */
    fun saveToGallery(context: Context, file: File, displayName: String): Uri {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("Не удалось создать файл в галерее")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        } else {
            // Android 8–9: пишем файл напрямую и сообщаем галерее.
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
            dir.mkdirs()
            val target = File(dir, displayName)
            file.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
            return Uri.fromFile(target)
        }
    }

    /** Ссылка, которую можно отдать другим приложениям (плеер, «Поделиться»). */
    fun shareableUri(context: Context, stored: Uri): Uri =
        if (stored.scheme == "file") {
            FileProvider.getUriForFile(context, "${context.packageName}.files", File(stored.path!!))
        } else {
            stored
        }

    fun delete(context: Context, stored: Uri?) {
        if (stored == null) return
        runCatching {
            if (stored.scheme == "file") File(stored.path!!).delete()
            else context.contentResolver.delete(stored, null, null)
        }
    }

    fun exists(context: Context, stored: Uri): Boolean = runCatching {
        if (stored.scheme == "file") File(stored.path!!).exists()
        else context.contentResolver.openFileDescriptor(stored, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /** Свободное место во внутренней памяти телефона. */
    fun freeBytes(context: Context): Long = runCatching {
        StatFs(context.filesDir.absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    fun hasEnoughSpace(context: Context) = freeBytes(context) >= MIN_FREE_BYTES

    fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f ГБ", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.0f МБ", bytes / (1024.0 * 1024))
        else -> "${bytes / 1024} КБ"
    }

    /** Папка для «сырых» кусков эфира (чтобы можно было нарезать заново). */
    fun sourcesDir(context: Context) = File(context.filesDir, "sources").apply { mkdirs() }

    /** Папка временных файлов нарезки. */
    fun workDir(context: Context, name: String) = File(context.cacheDir, "work/$name").apply { mkdirs() }

    fun dirSize(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
