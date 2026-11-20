package app.clipradar.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clipradar.BuildConfig
import app.clipradar.util.AppLog

/** Экран «Журнал»: все ошибки и важные события. Кнопка «Скопировать» — чтобы отправить разработчику. */
@Composable
fun JournalScreen(onCopied: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(AppLog.read()) }
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                clipboard.setText(AnnotatedString("Clip Radar ${BuildConfig.VERSION_NAME}\n" + text))
                onCopied()
            }) { Text("Скопировать") }
            OutlinedButton(onClick = { text = AppLog.read() }) { Text("Обновить") }
            OutlinedButton(onClick = { AppLog.clear(); text = "" }) { Text("Очистить") }
        }
        Text(
            "Здесь записаны ошибки и важные события. Если что-то не работает — нажмите «Скопировать» и отправьте текст.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer(Modifier.weight(1f).verticalScroll(scroll).horizontalScroll(rememberScrollState())) {
            Text(
                text.ifBlank { "Журнал пуст." },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
    }
}
