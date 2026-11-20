package app.clipradar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun StreamersScreen(vm: MainViewModel, onNeedBattery: () -> Unit) {
    val streamers by vm.streamers.collectAsState()
    val settings by vm.settings.collectAsState()
    val sessions by vm.sessions.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard {
                Text("Мои стримеры", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Добавьте ник или ссылку Twitch-канала. Как только стример выйдет в эфир, " +
                        "приложение пришлёт уведомление и само начнёт нарезать лучшие моменты.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Ник или twitch.tv/ник") },
                )
                Button(
                    onClick = {
                        if (vm.addStreamer(input)) {
                            input = ""
                            onNeedBattery()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Добавить") }
            }
        }
        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Всегда на страже", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (settings.guardMode) "Проверяю каждые 2 минуты (постоянное уведомление, тратит чуть больше батареи)"
                            else "Сейчас проверка раз в 15 минут — экономно, но начало стрима можно пропустить",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.guardMode, onCheckedChange = { on ->
                        vm.setGuard(on)
                        if (on) onNeedBattery()
                    })
                }
            }
        }
        if (streamers.isEmpty()) {
            item {
                Text(
                    "Пока никого нет.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        items(streamers, key = { it.login }) { s ->
            val watchingNow = sessions.any { it.channel == s.login }
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (if (s.isLive) "🔴 " else "⚪ ") + s.displayName,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when {
                                watchingNow -> "В эфире — слежу и нарезаю"
                                s.isLive -> "В эфире"
                                s.lastCheckedAt == 0L -> "Ещё не проверял"
                                else -> "Не в эфире"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(checked = s.watch, onCheckedChange = { vm.setWatch(s, it) })
                        Text("Следить", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton(onClick = { vm.removeStreamer(s) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Удалить")
                    }
                }
                if (s.isLive && !watchingNow) {
                    TextButton(onClick = { vm.watchStreamerNow(s); onNeedBattery() }) { Text("Следить за эфиром сейчас") }
                }
            }
        }
    }
}
