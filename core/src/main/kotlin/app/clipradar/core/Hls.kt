package app.clipradar.core

import java.net.URI

/** Один кусок в плейлисте HLS (.m3u8). */
data class HlsSegment(
    val sequence: Long,
    val durationSec: Double,
    val uri: String,
    val title: String?,
) {
    /** У Twitch реклама подписана не «live», а, например, «Amazon|...». */
    val isTwitchAd: Boolean get() = title != null && title.isNotBlank() && !title.trim().equals("live", ignoreCase = true)
}

data class HlsVariant(val uri: String, val bandwidth: Long, val height: Int?)

data class HlsPlaylist(
    val targetDurationSec: Double,
    val mediaSequence: Long,
    val segments: List<HlsSegment>,
    val endList: Boolean,
    val variants: List<HlsVariant>,
    /** Для fMP4-потоков: «шапка» (init-сегмент), которую нужно поставить в начало склейки. */
    val initUri: String? = null,
) {
    val isMaster: Boolean get() = variants.isNotEmpty()
}

/** Разбирает текст плейлиста .m3u8. */
object HlsParser {
    fun parse(text: String, baseUrl: String? = null): HlsPlaylist {
        var target = 2.0
        var mediaSeq = 0L
        var endList = false
        val segments = ArrayList<HlsSegment>()
        val variants = ArrayList<HlsVariant>()
        var pendingDuration: Double? = null
        var pendingTitle: String? = null
        var pendingVariant: Pair<Long, Int?>? = null
        var initUri: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-TARGETDURATION:") ->
                    target = line.substringAfter(':').toDoubleOrNull() ?: target
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") ->
                    mediaSeq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-ENDLIST") -> endList = true
                line.startsWith("#EXT-X-MAP:") ->
                    Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let { initUri = resolve(baseUrl, it) }
                line.startsWith("#EXTINF:") -> {
                    val body = line.substringAfter(':')
                    pendingDuration = body.substringBefore(',').trim().toDoubleOrNull() ?: target
                    pendingTitle = if (body.contains(',')) body.substringAfter(',') else null
                }
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val attrs = line.substringAfter(':')
                    val bw = Regex("(?:^|,)BANDWIDTH=(\\d+)").find(attrs)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val h = Regex("RESOLUTION=\\d+x(\\d+)").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                    pendingVariant = bw to h
                }
                line.startsWith("#") -> Unit // другие теги нам не нужны
                else -> {
                    val uri = resolve(baseUrl, line)
                    val v = pendingVariant
                    if (v != null) {
                        variants += HlsVariant(uri, v.first, v.second)
                        pendingVariant = null
                    } else {
                        val seq = mediaSeq + segments.size
                        segments += HlsSegment(seq, pendingDuration ?: target, uri, pendingTitle)
                        pendingDuration = null
                        pendingTitle = null
                    }
                }
            }
        }
        return HlsPlaylist(target, mediaSeq, segments, endList, variants, initUri)
    }

    /** Выбирает лучшее качество не выше [maxHeight]. */
    fun pickVariant(variants: List<HlsVariant>, maxHeight: Int): HlsVariant? {
        if (variants.isEmpty()) return null
        val fitting = variants.filter { (it.height ?: 0) <= maxHeight && it.height != null }
        return (fitting.ifEmpty { listOf(variants.minBy { it.height ?: Int.MAX_VALUE }) })
            .maxWith(compareBy<HlsVariant> { it.height ?: 0 }.thenBy { it.bandwidth })
    }

    fun resolve(baseUrl: String?, ref: String): String {
        if (baseUrl == null || ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            URI(baseUrl).resolve(ref).toString()
        } catch (e: Exception) {
            ref
        }
    }
}
