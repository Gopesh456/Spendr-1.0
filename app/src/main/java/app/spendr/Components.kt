package app.spendr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Number that counts up/down to its new value. */
@Composable
fun AnimatedMoney(value: Double, style: TextStyle, modifier: Modifier = Modifier, color: Color = Color.Unspecified, prefix: String = "") {
    val a = remember { Animatable(0f) }
    LaunchedEffect(value) { a.animateTo(value.toFloat(), tween(900, easing = FastOutSlowInEasing)) }
    // float loses paise on big numbers mid-animation, so show the exact value once it settles
    Text(prefix + money(if (a.isRunning) Math.round(a.value).toDouble() else value), style = style, color = color, modifier = modifier, maxLines = 1)
}

/** Staggered fade + rise when a card first appears. */
@Composable
fun Modifier.enter(index: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 70L)
        a.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow))
    }
    return graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 80f }
}

fun limitColor(fraction: Float, ok: Color) = when {
    fraction >= 1f -> SpendRed
    fraction >= 0.8f -> WarnAmber
    else -> ok
}

@Composable
fun LimitBar(
    fraction: Float, modifier: Modifier = Modifier, ok: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceVariant, height: Dp = 10.dp, colorize: Boolean = true,
) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessVeryLow), label = "bar")
    val c by animateColorAsState(if (colorize) limitColor(fraction, ok) else ok, label = "barColor")
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(track)) {
        Box(Modifier.fillMaxWidth(f).height(height).clip(CircleShape).background(c))
    }
}

@Composable
fun Ring(fraction: Float, modifier: Modifier = Modifier, stroke: Dp = 12.dp, ok: Color = MaterialTheme.colorScheme.primary, content: @Composable () -> Unit) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(1100, easing = FastOutSlowInEasing), label = "ring")
    val c by animateColorAsState(limitColor(fraction, ok), label = "ringColor")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val box = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, Offset(w / 2, w / 2), box, style = Stroke(w))
            drawArc(c, -90f, 360f * f, false, Offset(w / 2, w / 2), box, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Animated donut; slices sweep in on first draw. */
@Composable
fun Donut(slices: List<Pair<Color, Double>>, modifier: Modifier = Modifier, stroke: Dp = 18.dp, content: @Composable () -> Unit) {
    val total = slices.sumOf { it.second }.takeIf { it > 0 } ?: 1.0
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(slices) { sweep.snapTo(0f); sweep.animateTo(1f, tween(1200, easing = FastOutSlowInEasing)) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val tl = Offset(w / 2, w / 2)
            val box = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, tl, box, style = Stroke(w))
            var start = -90f
            for ((color, v) in slices) {
                val s = (v / total * 360f).toFloat() * sweep.value
                // a small gap between slices reads cleaner than butt-joined arcs
                if (s > 2f) drawArc(color, start + 1f, s - 2f, false, tl, box, style = Stroke(w, cap = StrokeCap.Butt))
                start += s
            }
        }
        content()
    }
}

@Composable
fun EmojiAvatar(emoji: String, tint: Color, size: Dp = 46.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Text(emoji, fontSize = (size.value * 0.45f).sp) }
}

@Composable
fun InitialAvatar(name: String, size: Dp = 46.dp) {
    val c = colorFor(name)
    Box(Modifier.size(size).clip(CircleShape).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase().ifBlank { "?" }, color = c, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")
private val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM")
private val fullFmt = DateTimeFormatter.ofPattern("d MMM yyyy")

fun timeText(t: Txn): String = Instant.ofEpochMilli(t.time).atZone(java.time.ZoneId.systemDefault()).format(timeFmt)
fun dayLabel(d: LocalDate): String = when (d) {
    LocalDate.now() -> "Today"
    LocalDate.now().minusDays(1) -> "Yesterday"
    else -> if (d.year == LocalDate.now().year) d.format(dayFmt) else d.format(fullFmt)
}
fun fullDate(d: LocalDate): String = d.format(fullFmt)

@Composable
fun TxnRow(t: Txn, modifier: Modifier = Modifier, lent: Double = 0.0, refunded: Double = 0.0, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmojiAvatar(t.emoji(), if (t.kind == Kind.SPENT) colorFor(t.category) else colorFor(t.kind.label))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                if (t.kind == Kind.SPENT) t.category else t.kind.label,
                t.person.takeIf { it.isNotBlank() && t.place.isNotBlank() },
                if (lent > 0) "🤝 ${money(lent)} lent" else null,
                if (refunded > 0) "🔙 ${money(refunded)} refunded" else null,
                timeText(t),
            ).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        val color = when {
            t.kind == Kind.EXCLUDED -> MaterialTheme.colorScheme.onSurfaceVariant
            t.isOut -> MaterialTheme.colorScheme.onSurface
            else -> IncomeGreen
        }
        // a split or refunded payment shows what it cost you, with the full bill struck through under it
        val carved = if (t.kind == Kind.SPENT) lent + refunded else 0.0
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (t.isOut) "−" else "+") + money(if (carved > 0) (t.amount - carved).coerceAtLeast(0.0) else t.amount),
                color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
            )
            if (carved > 0) Text(
                money(t.amount), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
            )
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        action?.invoke()
    }
}

@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 1.dp, shadowElevation = 1.dp) {
        Box(Modifier.padding(18.dp)) { content() }
    }

@Composable
fun EmptyState(emoji: String, title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val bob = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            while (true) { bob.animateTo(1f, tween(1400)); bob.animateTo(0f, tween(1400)) }
        }
        Text(emoji, fontSize = 64.sp, modifier = Modifier.graphicsLayer { translationY = -12f * bob.value })
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePick(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val st = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton({
                st.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    ) { DatePicker(st) }
}
