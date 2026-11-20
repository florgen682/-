package app.clipradar.tools

import app.clipradar.core.BoundaryPicker
import app.clipradar.core.BoundsConfig
import app.clipradar.core.HeatmapSegment
import app.clipradar.core.InterestScorer
import app.clipradar.core.LoudnessMeter
import app.clipradar.core.MomentContext
import app.clipradar.core.MomentMerger
import app.clipradar.core.PeakFinder
import app.clipradar.core.Subtitles
import app.clipradar.core.TimeFormat
import app.clipradar.core.TimeRange
import app.clipradar.core.Word
import app.clipradar.core.CaptionWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Проверка на настоящих роликах тем же кодом, что в приложении.
 *   peaks <meta.json>                       — пики графика пересмотров (как «Найти моменты»)
 *   bounds <wav> <words.json> <scenes.txt> <peakSec> [mustStart mustEnd] — умные границы
 */
fun main(args: Array<String>) {
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    when (args.firstOrNull()) {
        "peaks" -> peaks(File(args[1]))
        "audio-peaks" -> audioPeaks(File(args[1]), args[2].toDouble(), args.getOrNull(3)?.toDouble())
        "bounds" -> bounds(args)
        else -> error("usage: peaks <meta.json> | bounds <wav> <words.json> <scenes.txt> <peak> [mustStart mustEnd]")
    }
}

private fun peaks(meta: File) {
    val o = JSONObject(meta.readText())
    val duration = o.optDouble("duration")
    val heat = o.optJSONArray("heatmap") ?: JSONArray()
    val segs = (0 until heat.length()).map { heat.getJSONObject(it) }.map { HeatmapSegment(it.getDouble("start_time"), it.getDouble("end_time"), it.getDouble("value")) }
    if (segs.isEmpty()) {
        println("[]")
        return
    }
    val points = PeakFinder.heatmapToPoints(segs)
    val max = points.maxOf { it.value }
    val median = PeakFinder.median(points.map { it.value })
    val ignore = if (duration > 180) 10.0 else 0.0
    val found = PeakFinder.findTopPeaks(points, 8, 30.0, ignoreBeforeSec = ignore)
    val groups = MomentMerger.merge(found.map { TimeRange(it.timeSec - 10, it.timeSec + 10) }, 20.0)
        .map { g ->
            val members = g.second.map { found[it] }
            val best = members.maxBy { it.value }
            val score = InterestScorer.score(app.clipradar.core.MomentSignals(heatmap = InterestScorer.heatmapStrength(best.value, max, median))).score
            Triple(g, members, best) to score
        }
        .sortedByDescending { it.second }
        .take(5)
    val out = JSONArray()
    for ((t, score) in groups) {
        val (g, members, best) = t
        val winStart = maxOf(0.0, g.first.startSec + 10 - 60)
        val winEnd = minOf(duration, g.first.endSec - 10 + 60)
        out.put(
            JSONObject()
                .put("peak", best.timeSec).put("score", score).put("winStart", winStart).put("winEnd", winEnd)
                .put("members", members.size)
                .put("mustStart", if (members.size > 1) members.minOf { it.timeSec } - 3 else JSONObject.NULL)
                .put("mustEnd", if (members.size > 1) members.maxOf { it.timeSec } + 3 else JSONObject.NULL),
        )
    }
    println(out.toString(2))
}

/** Записи без графика пересмотров (Twitch): пики по громкости, как в приложении. [offset] — с какой секунды записи начинается WAV. */
private fun audioPeaks(wav: File, offset: Double, duration: Double?) {
    val half = loudness(wav)
    val perSec = half.chunked(2).map { it.average() }
    val found = app.clipradar.core.Loudness.loudestPeaks(perSec, 8, 30.0)
    val groups = MomentMerger.merge(found.map { TimeRange(it.timeSec - 10, it.timeSec + 10) }, 20.0)
        .map { g ->
            val members = g.second.map { found[it] }
            val best = members.maxBy { it.value }
            val jump = app.clipradar.core.Loudness.jumpDb(perSec, best.timeSec)
            val score = InterestScorer.score(app.clipradar.core.MomentSignals(audioJumpDb = jump)).score
            Triple(g, members, best) to score
        }
        .sortedByDescending { it.second }
        .take(5)
    val out = JSONArray()
    for ((t, score) in groups) {
        val (g, members, best) = t
        val winStart = maxOf(0.0, offset + g.first.startSec + 10 - 60)
        val winEnd = offset + g.first.endSec - 10 + 60
        out.put(
            JSONObject()
                .put("peak", offset + best.timeSec).put("score", score).put("winStart", winStart)
                .put("winEnd", if (duration != null) minOf(duration, winEnd) else winEnd)
                .put("members", members.size)
                .put("mustStart", if (members.size > 1) offset + members.minOf { it.timeSec } - 3 else JSONObject.NULL)
                .put("mustEnd", if (members.size > 1) offset + members.maxOf { it.timeSec } + 3 else JSONObject.NULL),
        )
    }
    println(out.toString(2))
}

/** Громкость каждые 0.5 с из WAV (16 бит). */
private fun loudness(wav: File): List<Double> {
    val bytes = wav.readBytes()
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var pos = 12
    var rate = 16000
    var channels = 1
    var dataStart = -1
    var dataLen = 0
    while (pos + 8 <= bytes.size) {
        val id = String(bytes, pos, 4)
        val len = bb.getInt(pos + 4)
        if (id == "fmt ") {
            channels = bb.getShort(pos + 10).toInt()
            rate = bb.getInt(pos + 12)
        } else if (id == "data") {
            dataStart = pos + 8
            dataLen = minOf(len, bytes.size - dataStart)
            break
        }
        pos += 8 + len
    }
    val meter = LoudnessMeter(rate / 2, channels)
    var i = dataStart
    while (i + 1 < dataStart + dataLen) {
        meter.feed(bb.getShort(i) / 32768f)
        i += 2
    }
    return meter.finish()
}

/** Слова из whisper-cli (-oj -ml 1 -sow): сегменты по одному слову. */
private fun words(json: File): List<Word> {
    val arr = JSONObject(json.readText()).getJSONArray("transcription")
    return (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { o ->
        val text = o.getString("text").trim()
        if (text.isEmpty()) return@mapNotNull null
        val off = o.getJSONObject("offsets")
        Word(text, off.getInt("from") / 1000.0, off.getInt("to") / 1000.0)
    }
}

private fun bounds(args: Array<String>) {
    val wav = File(args[1])
    val ws = words(File(args[2]))
    val scenes = File(args[3]).readLines().mapNotNull { it.trim().toDoubleOrNull() }
    val peak = args[4].toDouble()
    val must = if (args.size >= 7 && args[5] != "-") TimeRange(args[5].toDouble(), args[6].toDouble()) else null
    val loud = loudness(wav)
    val duration = loud.size * 0.5
    val b = BoundaryPicker.pick(
        MomentContext(
            durationSec = duration, peakSec = peak, words = ws, loudnessDb = loud, loudnessStepSec = 0.5,
            sceneCuts = scenes, mustInclude = must,
        ),
        BoundsConfig(maxLenSec = 60.0),
    )
    fun around(t: Double): String {
        val before = ws.filter { it.end <= t + 0.01 }.takeLast(4).joinToString(" ") { it.text }
        val after = ws.filter { it.start >= t - 0.01 }.take(4).joinToString(" ") { it.text }
        return "«…$before ⟂ $after…»"
    }
    val clipWords = ws.filter { it.end > b.start && it.start < b.end }
    val texts = CaptionWriter.generate(null, null, null, clipWords, "youtube")
    val cues = Subtitles.cues(clipWords, b.start, b.end)
    val f = { v: Double -> String.format(Locale.US, "%.2f", v) }
    val o = JSONObject()
        .put("start", b.start).put("end", b.end).put("peak", b.peak).put("length", b.length)
        .put("startReason", b.startReason).put("endReason", b.endReason)
        .put("startContext", around(b.start)).put("endContext", around(b.end))
        .put("hook", b.hook?.let { JSONArray().put(it.startSec).put(it.endSec) } ?: JSONObject.NULL)
        .put("words", ws.size).put("scenes", scenes.size)
        .put("titles", JSONArray(texts.titles)).put("hookText", texts.hook)
        .put("subtitles", JSONArray(cues.take(6).map { "${f(it.start)}–${f(it.end)} ${it.text}" }))
    println(o.toString(2))
    System.err.println("bounds ${TimeFormat.duration(b.start)}..${TimeFormat.duration(b.end)}")
}
