package app.spendr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

@Composable
fun HomeScreen(
    txns: List<Txn>,
    hasSms: Boolean,
    onEnableSms: () -> Unit,
    onOpen: (Txn) -> Unit,
    onCategory: (String) -> Unit,
    onSeeAll: () -> Unit,
    onLimits: () -> Unit,
    onLent: () -> Unit,
) {
    val ctx = LocalContext.current
    val s = remember(txns) { Stats(txns) }
    val lim = remember(txns) { Limits(ctx) }
    val recent = remember(txns) { txns.forActivity().take(6) }
    val lent = remember(txns) { lentByParent(txns) }
    val refunded = remember(txns) { refundByParent(txns) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.enter(0).padding(top = 8.dp, start = 4.dp)) {
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(greeting(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            }
        }
        if (!hasSms) item { SmsPromo(Modifier.enter(1), onEnableSms) }
        item { Hero(s, lim.monthly, Modifier.enter(2), onLimits) }
        item {
            Row(Modifier.enter(3).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CardBox(Modifier.weight(1f).fillMaxHeight()) { TodayRing(s.spentToday, lim.daily) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniStat("📈", "Invested", s.investedMonth, "Total ${money(s.investedTotal)}")
                    MiniStat("🤝", "Friends owe you", s.owed, if (s.owed > 0) "Tap to see who" else "All settled ✨", Modifier.clickable(onClick = onLent))
                }
            }
        }
        if (s.spentMonth > 0 || s.lastMonthToDate > 0) item { Insights(s, Modifier.enter(4)) }
        if (s.byCategory.isNotEmpty()) item { Categories(s, lim, Modifier.enter(5), onCategory) }
        item {
            SectionTitle("Recent", Modifier.enter(6)) { if (txns.isNotEmpty()) TextButton(onSeeAll) { Text("See all") } }
        }
        if (txns.isEmpty()) item {
            EmptyState("🪙", "No transactions yet", "Scan your bank SMS or tap + to add one by hand.")
        }
        items(recent, key = { it.id }) { t -> TxnRow(t, Modifier.animateItem(), lent[t.id] ?: 0.0, refunded[t.id] ?: 0.0) { onOpen(t) } }
    }
}

private fun greeting() = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Good morning ☀️"
    in 12..16 -> "Good afternoon 👋"
    in 17..21 -> "Good evening 🌆"
    else -> "Hey night owl 🦉"
}

@Composable
private fun SmsPromo(modifier: Modifier, onEnable: () -> Unit) {
    Box(modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MintBrush).padding(20.dp)) {
        Column {
            Text("📩  Track spending automatically", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Spendr reads bank & UPI SMS on your phone and logs every payment for you. Nothing ever leaves your device.",
                color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Button(onEnable, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF0B7A5E))) {
                Text("Enable SMS tracking", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Hero(s: Stats, monthly: Double, modifier: Modifier, onLimits: () -> Unit) {
    val white = Color.White
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(HeroBrush).padding(22.dp)) {
        Column {
            Text("Spent in ${s.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}", color = white.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall)
            AnimatedMoney(s.spentMonth, MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold), color = white)
            Spacer(Modifier.height(14.dp))
            if (monthly > 0) {
                LimitBar((s.spentMonth / monthly).toFloat(), ok = white, track = white.copy(alpha = 0.25f))
                Spacer(Modifier.height(8.dp))
                val left = monthly - s.spentMonth
                Text(
                    if (left >= 0) "${money(left)} left · ${money(left / s.daysLeft)}/day for ${s.daysLeft} days"
                    else "Over your ${money(monthly)} limit by ${money(-left)} 😬",
                    color = white, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                )
            } else {
                Text("Set a monthly limit →", color = white, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onLimits))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroChip("Received", s.receivedMonth, Modifier.weight(1f))
                HeroChip("Month-end pace", s.projected, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HeroChip(label: String, value: Double, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
        AnimatedMoney(value, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Color.White)
    }
}

@Composable
private fun TodayRing(spent: Double, daily: Double) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Ring(if (daily > 0) (spent / daily).toFloat() else 0f, Modifier.size(118.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Today", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AnimatedMoney(spent, MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (daily > 0) "of ${money(daily)} daily" else "No daily limit",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MiniStat(emoji: String, label: String, value: Double, sub: String, modifier: Modifier = Modifier) {
    CardBox(modifier) {
        Column {
            Text("$emoji  $label", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            AnimatedMoney(value, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Insights(s: Stats, modifier: Modifier) {
    CardBox(modifier) {
        Column {
            Text("Last 14 days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Bars(s.last14.map { it.second })
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                val f = DateTimeFormatter.ofPattern("d MMM")
                Text(s.last14.first().first.format(f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            if (s.lastMonthToDate > 0) {
                val pct = ((s.spentMonth - s.lastMonthToDate) / s.lastMonthToDate * 100).toInt()
                InsightLine(
                    if (pct <= 0) "📉" else "📈",
                    if (pct == 0) "Same pace as last month" else "${abs(pct)}% ${if (pct < 0) "less" else "more"} than last month by this date",
                    if (pct <= 0) IncomeGreen else SpendRed,
                )
            }
            s.biggest?.let { InsightLine("🏆", "Biggest spend: ${money(it.amount)} · ${it.title}") }
            s.favourite?.let { (name, n) -> InsightLine("🔁", "Most frequent: $name ($n times)") }
        }
    }
}

@Composable
private fun InsightLine(emoji: String, text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(emoji)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Bars(values: List<Double>) {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values) { grow.snapTo(0f); grow.animateTo(1f, tween(1000, easing = FastOutSlowInEasing)) }
    val c = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { idx, v ->
            val h = ((v / max).toFloat().coerceAtLeast(0.04f)) * grow.value
            Box(
                Modifier.weight(1f).fillMaxHeight(h).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                    .background(if (idx == values.lastIndex) c else c.copy(alpha = 0.3f))
            )
        }
    }
}

@Composable
private fun Categories(s: Stats, lim: Limits, modifier: Modifier, onCategory: (String) -> Unit) {
    CardBox(modifier) {
        Column {
            Text("Where it went", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Donut(s.byCategory.map { colorFor(it.first) to it.second }, Modifier.size(132.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${s.byCategory.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                        Text(if (s.byCategory.size == 1) "category" else "categories", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(18.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.byCategory.take(5).forEach { (c, v) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(colorFor(c)))
                            Spacer(Modifier.width(8.dp))
                            Text(c, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("${(v / s.spentMonth * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            s.byCategory.forEach { (c, v) ->
                val limit = lim.category(c)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onCategory(c) }.padding(vertical = 8.dp, horizontal = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(CATEGORIES[c] ?: "🏷️")
                        Spacer(Modifier.width(10.dp))
                        Text(c, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            money(v) + if (limit > 0) " / ${money(limit)}" else "",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LimitBar((if (limit > 0) v / limit else v / s.spentMonth).toFloat().let { if (limit > 0) it else it.coerceAtMost(0.79f) }, ok = colorFor(c), height = 7.dp)
                }
            }
        }
    }
}
