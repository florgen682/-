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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
            Text("Границы клипа", style = MaterialTheme.typography.titleMedium)
            Text(
                "Начало и конец выбираются сами: по речи (начало фразы, паузы), сменам сцен, чату и громкости. " +
                    "Клип не режется посреди слова. Минимум — 12 секунд.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Stepper("Максимальная длина", s.maxClipSec, 20..180, 5, { "$it с" }) { v -> vm.updateSettings { it.copy(maxClipSec = v) } }
            Stepper("Пауза между моментами эфира", s.cooldownSec, 30..600, 15, { "$it с" }) { v -> vm.updateSettings { it.copy(cooldownSec = v) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Хук в начале", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Если пик позже 3-й секунды — первые 1.5–2 с клипа показывают сам пик, затем нормальное начало",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.hookAtStart, onCheckedChange = { on -> vm.updateSettings { it.copy(hookAtStart = on) } })
            }
        }

        SectionCard {
            Text("Речь и субтитры", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Расшифровывать речь", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Whisper прямо на телефоне, без интернета и платных сервисов. Модель скачается один раз.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.speechEnabled, onCheckedChange = { on -> vm.updateSettings { it.copy(speechEnabled = on) } })
            }
            if (s.speechEnabled) {
                app.clipradar.speech.WhisperModel.entries.forEach { model ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = s.whisperModel == model.name, onClick = { vm.updateSettings { it.copy(whisperModel = model.name) } }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.whisperModel == model.name, onClick = { vm.updateSettings { it.copy(whisperModel = model.name) } })
                        Text(model.title)
                    }
                }
                Text("Язык речи", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("en" to "Английский", "ru" to "Русский", "auto" to "Авто").forEach { (code, title) ->
                        FilterChip(
                            selected = s.speechLanguage == code,
                            onClick = { vm.updateSettings { it.copy(speechLanguage = code) } },
                            label = { Text(title) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Субтитры на видео", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = s.subtitles, onCheckedChange = { on -> vm.updateSettings { it.copy(subtitles = on) } })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Текст-хук на видео", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Например, WAIT FOR IT — первые 2 секунды вверху кадра",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.hookTextOnVideo, onCheckedChange = { on -> vm.updateSettings { it.copy(hookTextOnVideo = on) } })
            }
        }

        SectionCard {
            Text("Подписи с помощью ИИ (необязательно)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Без ключа названия, хук и описание делаются по шаблонам. С ключом Claude API их пишет ИИ " +
                    "по расшифровке (это платно по тарифам Anthropic). Ключ хранится только на телефоне.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var key by remember(s.aiApiKey) { mutableStateOf(s.aiApiKey) }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("API-ключ ИИ") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.updateSettings { it.copy(aiApiKey = key.trim()) } }) { Text("Сохранить") }
                if (s.aiApiKey.isNotBlank()) {
                    OutlinedButton(onClick = { key = ""; vm.updateSettings { it.copy(aiApiKey = "") } }) { Text("Удалить ключ") }
                }
            }
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
            Text(
                "У огромных чатов (100+ сообщений за 10 с, как у крупных западных стримеров) планка ниже — " +
                    "там всплеск в 1,8 раза уже большой момент. Чат понимает русский и английский смех, эмоуты и эмодзи 😂💀.",
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
            Text("Готовый клип всегда 1080×1920 (9:16), частота кадров как у источника (30 или 60), звук AAC 192 кбит/с, громкость −14 LUFS.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Как укладывать видео в вертикальный кадр (чёрных полей нет ни в одном режиме)", style = MaterialTheme.typography.bodyLarge)
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
