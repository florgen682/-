package app.clipradar.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.clipradar.core.TimeFormat
import app.clipradar.data.MomentDetails
import app.clipradar.data.MomentStatus
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Экран клипа: предпросмотр, ручная правка границ, подписи для публикации, оценка момента. */
@Composable
fun ClipScreen(vm: MainViewModel, momentId: Long, onBack: () -> Unit) {
    val moment by vm.observeMoment(momentId).collectAsState(initial = null)
    val settings by vm.settings.collectAsState()
    val m = moment
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Назад") }
            Text(m?.title ?: "Клип", style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
        if (m == null) {
            Text("Момент удалён", modifier = Modifier.padding(16.dp))
            return@Column
        }
        val details = remember(m.details) { MomentDetails.parse(m.details) }
        val source = m.sourceFile?.let(::File)?.takeIf { it.exists() }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (source != null) {
                Editor(vm, m, source)
            } else {
                SectionCard {
                    Text(
                        if (m.isLive) "Исходник эфира удалён — правка недоступна."
                        else "Исходник ещё не скачан или удалён. Нажмите «Нарезать» — кусок скачается заново, после этого можно править вручную.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionCard {
                Text("Почему такие границы", fontWeight = FontWeight.SemiBold)
                Text("Оценка: ${m.score} · ${m.reason}", style = MaterialTheme.typography.bodySmall)
                details.startReason?.let { Text("Начало: $it", style = MaterialTheme.typography.bodySmall) }
                details.endReason?.let { Text("Конец: $it", style = MaterialTheme.typography.bodySmall) }
                details.layoutNote?.let { Text("Кадр: $it", style = MaterialTheme.typography.bodySmall) }
                details.badFraction?.let {
                    Text("Тишина/стоп-кадр: ${(it * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                if (m.status == MomentStatus.FAILED || m.status == MomentStatus.REJECTED) {
                    m.error?.let { Text(it, color = Hot, style = MaterialTheme.typography.bodySmall) }
                }
            }

            Feedback(vm, m.feedback) { good -> vm.feedback(m, good) }

            if (details.titles.isNotEmpty()) {
                Captions(details)
            } else if (m.status != MomentStatus.READY) {
                Text(
                    "Подписи для публикации появятся после нарезки.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (settings.aiApiKey.isBlank() && details.titles.isNotEmpty()) {
                Text(
                    "Подписи сделаны по шаблонам. Можно указать ключ ИИ в настройках — тогда их напишет ИИ.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Editor(vm: MainViewModel, m: app.clipradar.data.MomentEntity, source: File) {
    val context = LocalContext.current
    val player = remember(source.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(source)))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    var wave by remember(source.absolutePath) { mutableStateOf<List<Double>>(emptyList()) }
    LaunchedEffect(source.absolutePath) { wave = vm.waveform(source) }
    val duration = max(wave.size * 0.5, max(m.clipEndSec, m.manualEndSec ?: 0.0)).coerceAtLeast(1.0)

    val initStart = m.manualStartSec ?: m.clipStartSec.takeIf { m.clipEndSec > m.clipStartSec } ?: max(0.0, m.peakInSource - 15)
    val initEnd = m.manualEndSec ?: m.clipEndSec.takeIf { m.clipEndSec > m.clipStartSec } ?: min(duration, m.peakInSource + 15)
    var start by remember(m.id, initStart) { mutableStateOf(initStart) }
    var end by remember(m.id, initEnd) { mutableStateOf(initEnd) }

    // Предпросмотр крутится внутри выбранного отрезка.
    LaunchedEffect(start) {
        player.seekTo((start * 1000).toLong())
        player.play()
    }
    LaunchedEffect(player) {
        while (true) {
            delay(100)
            if (player.currentPosition >= (end * 1000).toLong() || player.currentPosition < (start * 1000).toLong() - 500) {
                player.seekTo((start * 1000).toLong())
            }
        }
    }

    SectionCard {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.Black),
        ) {
            AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } }, modifier = Modifier.fillMaxSize())
        }
        Waveform(wave, start, end, m.peakInSource)
        Text(
            "Начало ${fmt(start)} · конец ${fmt(end)} · длина ${fmt(end - start)} с",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text("Начало", style = MaterialTheme.typography.labelLarge)
        FineSlider(start, 0.0, duration) { start = it.coerceAtMost(end - 1.0) }
        Text("Конец", style = MaterialTheme.typography.labelLarge)
        FineSlider(end, 0.0, duration) { end = it.coerceAtLeast(start + 1.0) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.recut(m, start, end) }, modifier = Modifier.weight(1f)) { Text("Перерезать") }
            if (m.manualStartSec != null) {
                OutlinedButton(onClick = { vm.resetManual(m) }, modifier = Modifier.weight(1f)) { Text("Авто-границы") }
            }
        }
    }
}

/** Ползунок с шагом 0.1 с и кнопками «−0.1 / +0.1». */
@Composable
private fun FineSlider(value: Double, min: Double, max: Double, onChange: (Double) -> Unit) {
    fun round01(v: Double) = (v * 10).roundToInt() / 10.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange(round01((value - 0.1).coerceIn(min, max))) }) { Text("−0.1") }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(round01(it.toDouble())) },
            valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onChange(round01((value + 0.1).coerceIn(min, max))) }) { Text("+0.1") }
    }
}

/** Волна звука: столбики громкости, выбранный отрезок подсвечен, пик отмечен. */
@Composable
private fun Waveform(db: List<Double>, start: Double, end: Double, peak: Double) {
    val accent = Violet
    val dim = MaterialTheme.colorScheme.outline
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        if (db.isEmpty()) return@Canvas
        val total = db.size * 0.5
        val w = size.width / db.size
        val minDb = -60.0
        for ((i, v) in db.withIndex()) {
            val t = i * 0.5
            val k = ((v - minDb) / -minDb).coerceIn(0.02, 1.0)
            val h = (size.height * k).toFloat()
            drawRect(
                color = if (t in start..end) accent else dim,
                topLeft = Offset(i * w, (size.height - h) / 2),
                size = Size(max(1f, w * 0.8f), h),
            )
        }
        if (peak >= 0) {
            val x = (peak / total * size.width).toFloat()
            drawLine(Hot, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        }
    }
}

@Composable
private fun Feedback(vm: MainViewModel, current: Int, onPick: (Boolean) -> Unit) {
    SectionCard {
        Text("Это хороший момент?", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val goodSel = current == 1
            val badSel = current == -1
            if (goodSel) Button(onClick = { onPick(true) }, modifier = Modifier.weight(1f)) { Text("👍 Хороший") }
            else OutlinedButton(onClick = { onPick(true) }, modifier = Modifier.weight(1f)) { Text("👍 Хороший") }
            if (badSel) Button(onClick = { onPick(false) }, modifier = Modifier.weight(1f)) { Text("👎 Плохой") }
            else OutlinedButton(onClick = { onPick(false) }, modifier = Modifier.weight(1f)) { Text("👎 Плохой") }
        }
        Text(
            "Оценки запоминаются: для этого стримера порог и чувствительность чата подстраиваются.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Captions(d: MomentDetails) {
    val clipboard = LocalClipboardManager.current
    SectionCard {
        Text("Подписи для публикации" + if (d.captionsByAi) " (ИИ)" else "", fontWeight = FontWeight.SemiBold)
        Text("Названия", style = MaterialTheme.typography.labelLarge)
        for (t in d.titles) CopyRow(t) { clipboard.setText(AnnotatedString(t)) }
        d.hookText?.let { hook ->
            Text("Хук на первые 2 секунды", style = MaterialTheme.typography.labelLarge)
            CopyRow(hook) { clipboard.setText(AnnotatedString(hook)) }
        }
        d.description?.let { desc ->
            Text("Описание и хэштеги", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(desc, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { clipboard.setText(AnnotatedString(desc)) }) { Text("Копировать описание") }
        }
    }
}

@Composable
private fun CopyRow(text: String, onCopy: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(Modifier.weight(1f)) { Text(text, style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = onCopy) { Text("Копировать") }
    }
}

private fun fmt(v: Double) = if (v >= 60) TimeFormat.duration(v) + String.format(Locale.US, ".%d", ((v * 10).roundToInt() % 10)) else String.format(Locale.US, "%.1f", v)
