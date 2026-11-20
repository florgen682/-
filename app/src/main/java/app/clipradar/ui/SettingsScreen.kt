package app.clipradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.clipradar.data.ClipLayout
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBattery: () -> Unit,
    onNotifications: () -> Unit,
) {
    val s by vm.settings.collectAsState()
    val ytdlp by vm.ytdlpVersion.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard {
            Text("Длина клипа", style = MaterialTheme.typography.titleMedium)
            Text("Готовые видео", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Stepper("До момента", s.vodBeforeSec, 5..60, 5, { "$it с" }) { v -> vm.updateSettings { it.copy(vodBeforeSec = v) } }
            Stepper("После момента", s.vodAfterSec, 5..60, 5, { "$it с" }) { v -> vm.updateSettings { it.copy(vodAfterSec = v) } }
            Text("Прямые эфиры", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Stepper("До момента", s.liveBeforeSec, 10..90, 5, { "$it с" }) { v -> vm.updateSettings { it.copy(liveBeforeSec = v) } }
            Stepper("После момента", s.liveAfterSec, 5..60, 5, { "$it с" }) { v -> vm.updateSettings { it.copy(liveAfterSec = v) } }
            Stepper("Пауза между клипами", s.cooldownSec, 30..600, 15, { "$it с" }) { v -> vm.updateSettings { it.copy(cooldownSec = v) } }
        }

        SectionCard {
            Text("Что считать моментом", style = MaterialTheme.typography.titleMedium)
            Text("Чувствительность чата", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Низкая", "Обычная", "Высокая").forEachIndexed { i, title ->
                    FilterChip(
                        selected = s.chatSensitivity == i,
                        onClick = { vm.updateSettings { it.copy(chatSensitivity = i) } },
                        label = { Text(title) },
                    )
                }
            }
            Text(
                when (s.chatSensitivity) {
                    0 -> "Момент — когда чат в 4 раза активнее обычного и больше 25 сообщений за 10 с"
                    2 -> "Момент — когда чат в 2 раза активнее обычного и больше 8 сообщений за 10 с"
                    else -> "Момент — когда чат в 3 раза активнее обычного и больше 15 сообщений за 10 с"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Порог оценки: ${s.scoreThreshold}", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = s.scoreThreshold.toFloat(),
                onValueChange = { v -> vm.updateSettings { it.copy(scoreThreshold = v.roundToInt()) } },
                valueRange = 0f..100f,
                steps = 19,
            )
            Text(
                "Моменты с оценкой ниже порога сохраняются как «слабые» и не режутся автоматически.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            Text("Видео", style = MaterialTheme.typography.titleMedium)
            Text("Качество скачивания", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(480, 720, 1080).forEach { q ->
                    FilterChip(
                        selected = s.quality == q,
                        onClick = { vm.updateSettings { it.copy(quality = q) } },
                        label = { Text("${q}p") },
                    )
                }
            }
            Text("Готовый клип всегда 1080×1920 (9:16). Эфиры пишутся максимум в 720p.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Как укладывать видео в вертикальный кадр", style = MaterialTheme.typography.bodyLarge)
            ClipLayout.entries.forEach { l ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = s.layout == l, onClick = { vm.updateSettings { it.copy(layout = l) } }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.layout == l, onClick = { vm.updateSettings { it.copy(layout = l) } })
                    Text(l.title)
                }
            }
        }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Тихо ночью", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Ночью не присылать уведомления о каждом моменте — только итог утром",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.quietEnabled, onCheckedChange = { on -> vm.updateSettings { it.copy(quietEnabled = on) } })
            }
            if (s.quietEnabled) {
                Stepper("С", s.quietStartMin, 0..(24 * 60 - 30), 30, ::hhmm) { v -> vm.updateSettings { it.copy(quietStartMin = v) } }
                Stepper("До", s.quietEndMin, 0..(24 * 60 - 30), 30, ::hhmm) { v -> vm.updateSettings { it.copy(quietEndMin = v) } }
            }
        }

        SectionCard {
            Text("Система", style = MaterialTheme.typography.titleMedium)
            Text("Версия yt-dlp: $ytdlp", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { vm.updateYtDlp() }, modifier = Modifier.fillMaxWidth()) { Text("Обновить yt-dlp") }
            OutlinedButton(onClick = onBattery, modifier = Modifier.fillMaxWidth()) { Text("Не экономить батарею для Clip Radar") }
            OutlinedButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth()) { Text("Разрешить уведомления") }
            Text(
                "Чтобы наблюдение не выключалось в фоне, разрешите приложению работать без ограничений батареи.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)
