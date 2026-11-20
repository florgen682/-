package app.clipradar.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.clipradar.core.TimeFormat
import app.clipradar.data.MomentEntity
import app.clipradar.data.MomentStatus
import app.clipradar.live.LiveSession
import app.clipradar.media.Gallery
import app.clipradar.work.ClipProgress
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis

@Composable
fun MomentsScreen(vm: MainViewModel, initialLink: String?, onNeedBattery: () -> Unit) {
    val moments by vm.moments.collectAsState()
    val settings by vm.settings.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val analyze by vm.analyze.collectAsState()
    val storage by vm.storage.collectAsState()
    var link by rememberSaveable(initialLink) { mutableStateOf(initialLink ?: "") }
    var showCleanup by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ссылка на видео или эфир") },
                    placeholder = { Text("youtube.com/… или twitch.tv/ник") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.findMoments(link) }, modifier = Modifier.weight(1f)) {
                        Text("Найти моменты")
                    }
                    OutlinedButton(
                        onClick = { if (vm.watchLive(link)) onNeedBattery() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Следить за эфиром") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Нарезать автоматически", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (settings.autoCut) "Клипы с оценкой от ${settings.scoreThreshold} режутся сами"
                            else "Только уведомления — резать по кнопке",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.autoCut, onCheckedChange = { on -> vm.updateSettings { it.copy(autoCut = on) } })
                }
            }
        }

        analyze?.let { a ->
            item {
                SectionCard {
                    if (a.running) {
                        Text("🔎 Ищу моменты", fontWeight = FontWeight.SemiBold)
                        Text(a.stage ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else if (a.error != null) {
                        Text("⚠️ ${a.error}", color = Warn)
                    } else if (a.result != null) {
                        Text("✅ ${a.result}")
                    }
                }
            }
        }

        if (sessions.isNotEmpty()) {
            item {
                SectionCard {
                    Text("Слежу за эфиром…", fontWeight = FontWeight.SemiBold)
                    for (s in sessions) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("🔴 ${s.channel}", fontWeight = FontWeight.SemiBold)
                                Text(
                                    (if (s.chatConnected) "Чат: ${s.chatPer10s} за 10 с (обычно ${"%.0f".format(s.baseline)})" else "Подключаюсь к чату…") +
                                        " · буфер ${s.bufferSec} с · моментов ${s.moments}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { vm.stopWatching(s.channel) }) { Text("Стоп") }
                        }
                    }
                }
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Клипы: ${Gallery.formatSize(storage.clipsBytes)} · исходники эфиров: ${Gallery.formatSize(storage.sourcesBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Свободно: ${if (storage.freeBytes == Long.MAX_VALUE) "—" else Gallery.formatSize(storage.freeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (storage.lowSpace) Hot else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { showCleanup = true }) { Text("Удалить старые") }
                }
                if (storage.lowSpace) {
                    Text("⚠️ Меньше 1 ГБ свободно — новые клипы не режутся. Удалите старые клипы.", color = Hot)
                }
            }
        }

        if (moments.isEmpty()) {
            item {
                Text(
                    "Здесь появятся найденные моменты. Вставьте ссылку на видео YouTube или запись Twitch и нажмите «Найти моменты». " +
                        "Для прямого эфира Twitch — «Следить за эфиром».",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }

        items(moments, key = { it.id }) { m ->
            MomentCard(m, settings.scoreThreshold, vm)
        }
    }

    if (showCleanup) {
        AlertDialog(
            onDismissRequest = { showCleanup = false },
            title = { Text("Удалить старые клипы") },
            text = { Text("Клипы удаляются из галереи вместе с исходниками эфиров. Что удалить?") },
            confirmButton = {
                TextButton(onClick = { vm.deleteOld(olderThanDays = 7); showCleanup = false }) { Text("Старше 7 дней") }
            },
            dismissButton = {
                TextButton(onClick = { vm.deleteOld(keepLast = 10); showCleanup = false }) { Text("Оставить 10 новых") }
            },
        )
    }
}

private fun statusText(status: String) = when (status) {
    MomentStatus.FOUND -> "найден"
    MomentStatus.WEAK -> "слабый"
    MomentStatus.QUEUED -> "в очереди"
    MomentStatus.CUTTING -> "режется…"
    MomentStatus.READY -> "готов"
    MomentStatus.FAILED -> "ошибка"
    else -> status
}

@Composable
private fun MomentCard(m: MomentEntity, threshold: Int, vm: MainViewModel) {
    val context = LocalContext.current
    val progress by ClipProgress.state.collectAsState()
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(m)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(m.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (m.isLive) "Эфир: ${LiveSession.momentTime(m)}" else "Время: ${TimeFormat.duration(m.positionSec)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Причина: ${m.reason}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Статус: ${statusText(m.status)}", style = MaterialTheme.typography.bodySmall)
                    if (m.status == MomentStatus.CUTTING) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    }
                }
                progress[m.id]?.takeIf { m.status == MomentStatus.CUTTING }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Violet)
                }
                m.error?.takeIf { m.status == MomentStatus.FAILED }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Hot)
                }
            }
            ScoreBadge(m.score, threshold)
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (m.status == MomentStatus.READY && m.clipUri != null) {
                IconButton(onClick = { openClip(context, Uri.parse(m.clipUri)) }) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Открыть")
                }
                IconButton(onClick = { shareClip(context, Uri.parse(m.clipUri)) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Поделиться")
                }
            }
            if (m.status != MomentStatus.QUEUED && m.status != MomentStatus.CUTTING) {
                TextButton(onClick = { vm.cut(m) }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (m.status == MomentStatus.READY || m.status == MomentStatus.FAILED) "Нарезать заново" else "Нарезать")
                }
            }
            IconButton(onClick = { vm.delete(m) }) {
                Icon(Icons.Filled.Delete, contentDescription = "Удалить")
            }
        }
    }
}

@Composable
private fun Thumbnail(m: MomentEntity) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (m.status == MomentStatus.READY && m.clipUri != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(Uri.parse(m.clipUri)).videoFrameMillis(1500).build(),
                contentDescription = "Превью клипа",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(if (m.isLive) "🔴" else "🎬")
        }
    }
    Spacer(Modifier.height(0.dp))
}

private fun openClip(context: Context, stored: Uri) {
    val uri = Gallery.shareableUri(context, stored)
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Нет приложения для просмотра видео", Toast.LENGTH_SHORT).show()
    }
}

private fun shareClip(context: Context, stored: Uri) {
    val uri = Gallery.shareableUri(context, stored)
    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Поделиться клипом"))
}
