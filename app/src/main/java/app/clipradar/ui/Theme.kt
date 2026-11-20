package app.clipradar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Violet = Color(0xFF9B6BFF)
val Hot = Color(0xFFFF4F6D)
val Good = Color(0xFF3DDC97)
val Warn = Color(0xFFFFC24B)

private val colors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    secondary = Hot,
    background = Color(0xFF101014),
    surface = Color(0xFF17171D),
    surfaceVariant = Color(0xFF22222B),
    onSurface = Color(0xFFECECF1),
    onSurfaceVariant = Color(0xFFA7A7B4),
    outline = Color(0xFF3A3A46),
)

@Composable
fun ClipRadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}

/** Цвет оценки: зелёный — отлично, жёлтый — средне, серый — слабо. */
fun scoreColor(score: Int, threshold: Int): Color = when {
    score >= maxOf(threshold, 80) -> Good
    score >= threshold -> Warn
    else -> Color(0xFF8A8A99)
}

@Composable
fun ScoreBadge(score: Int, threshold: Int) {
    val c = scoreColor(score, threshold)
    Text(
        text = "$score",
        color = Color.Black,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        modifier = Modifier
            .background(c, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Число с кнопками «−» и «+». */
@Composable
fun Stepper(
    label: String,
    value: Int,
    range: IntRange,
    step: Int = 1,
    format: (Int) -> String = { "$it" },
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        OutlinedIconButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) {
            Text("−", fontSize = 18.sp)
        }
        Text(
            format(value),
            modifier = Modifier.width(72.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedIconButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) {
            Text("+", fontSize = 18.sp)
        }
        Spacer(Modifier.width(2.dp))
    }
}
