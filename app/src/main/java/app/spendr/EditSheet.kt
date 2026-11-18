package app.spendr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditSheet(initial: Txn, txns: List<Txn>, onDismiss: () -> Unit, onSave: (Txn) -> Unit, onDelete: (Txn) -> Unit, onSplit: (Txn) -> Unit) {
    var amount by remember { mutableStateOf(if (initial.amount > 0) plain(initial.amount) else "") }
    var isOut by remember { mutableStateOf(initial.kind.out ?: initial.isOut) }
    var kind by remember { mutableStateOf(initial.kind) }
    var category by remember { mutableStateOf(initial.category) }
    var person by remember { mutableStateOf(initial.person) }
    var place by remember { mutableStateOf(initial.place) }
    var note by remember { mutableStateOf(initial.note) }
    var time by remember { mutableLongStateOf(initial.time) }
    var loanId by remember { mutableStateOf(initial.loanId) }
    var parentId by remember { mutableStateOf(initial.parentId) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val dt = Instant.ofEpochMilli(time).atZone(zone).toLocalDateTime()

    val loans = remember(txns) {
        val open = openLoans(txns)
        // keep a fully-paid loan pickable if this repayment already points at it
        val linked = txns.firstOrNull { it.id == initial.loanId && it.kind == Kind.LENT && open.none { o -> o.lent.id == it.id } }
        open.map { it.lent to it.left } + listOfNotNull(linked?.let { it to 0.0 })
    }
    val cats = remember(txns) { CATEGORIES.keys + txns.map { it.category }.filter { it.isNotBlank() && it !in CATEGORIES }.distinct() }
    val value = amount.toDoubleOrNull()
    val valid = value != null && value > 0 && (kind != Kind.LENT || person.isNotBlank())

    val byId = remember(txns) { txns.associateBy { it.id } }
    val lentFrom = remember(txns) { lentByParent(txns) }
    val kids = remember(txns) { txns.filter { it.kind == Kind.LENT && it.parentId == initial.id && initial.id != 0L } }
    // payments around this date you might have covered for a friend
    val candidates = remember(txns, time) {
        val d = time.toDate()
        val near = txns.filter { it.kind == Kind.SPENT && it.id != initial.id && abs(ChronoUnit.DAYS.between(it.date, d)) <= 7 }
            .sortedBy { abs(it.time - time) }.take(8)
        near + listOfNotNull(byId[initial.parentId]?.takeIf { p -> near.none { it.id == p.id } })
    }
    // purchases in the 60 days before a refund, the ones it could fit (same amount or up to ₹50 less) first
    val refundable = remember(txns, time, value) {
        val r = value ?: 0.0
        val before = txns.filter { it.kind == Kind.SPENT && it.isOut && it.time <= time && time - it.time <= 60 * 86_400_000L }
        val fit = before.filter { r > 0 && r <= it.amount + 0.005 && r >= it.amount - REFUND_FEE_WINDOW }.sortedBy { it.amount - r }
        (fit + before.sortedByDescending { it.time }).distinctBy { it.id }.take(8)
            .let { list -> list + listOfNotNull(byId[initial.parentId]?.takeIf { p -> list.none { it.id == p.id } }) }
    }
    val refunds = remember(txns) { txns.filter { it.kind == Kind.REFUND && it.parentId == initial.id && initial.id != 0L } }
    /** What's left of a payment after other friends' parts (not counting this loan's own old amount). */
    fun room(p: Txn) = p.amount - (lentFrom[p.id] ?: 0.0) + (if (initial.parentId == p.id) initial.amount else 0.0)
    fun result() = initial.copy(
        amount = value!!, isOut = kind.out ?: isOut, kind = kind, category = category.ifBlank { "Other" },
        person = person, place = place, note = note, time = time,
        loanId = if (kind == Kind.REPAY) loanId else null,
        parentId = if (kind == Kind.LENT || kind == Kind.REFUND) parentId else null,
    )

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text(if (initial.id == 0L) "New transaction" else "Edit transaction", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            val amtColor by animateColorAsState(if (isOut) MaterialTheme.colorScheme.onSurface else IncomeGreen, label = "amt")
            TextField(
                amount, { s -> amount = s.filter { it.isDigit() || it == '.' } }, Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.ExtraBold, color = amtColor, textAlign = TextAlign.Center),
                placeholder = { Text("0", style = MaterialTheme.typography.displayMedium, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                prefix = { Text("₹", style = MaterialTheme.typography.displaySmall, color = amtColor) },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true to "💸  Paid", false to "💰  Received").forEachIndexed { i, (out, label) ->
                    SegmentedButton(
                        selected = isOut == out,
                        onClick = {
                            isOut = out
                            if (kind.out != null && kind.out != out) kind = if (out) Kind.SPENT else Kind.RECEIVED
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            Label("Type")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Kind.entries.filter { it.out == null || it.out == isOut }.forEach { k ->
                    FilterChip(kind == k, { kind = k }, label = { Text("${k.emoji}  ${k.label}") })
                }
            }
            AnimatedVisibility(kind == Kind.EXCLUDED) {
                Hint("Excluded money isn't counted anywhere — use it for transfers to your own accounts or credit-card bill payments.")
            }
            AnimatedVisibility(kind == Kind.SPENT) {
                Column {
                    Label("Category")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        cats.forEach { c -> CategoryTile(c, category == c) { category = c } }
                    }
                    Spacer(Modifier.height(8.dp))
                    var custom by remember { mutableStateOf(if (initial.category !in cats) initial.category else "") }
                    OutlinedTextField(
                        custom, { custom = it; if (it.isNotBlank()) category = it.trim() }, Modifier.fillMaxWidth(),
                        placeholder = { Text("Or type a new category") }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    )
                }
            }
            AnimatedVisibility(kind == Kind.REPAY) {
                Column {
                    Label("Which loan is this paying back?")
                    if (loans.isEmpty()) Hint("No open loans. Mark a payment as Lent first.")
                    loans.forEach { (l, left) ->
                        PickCard(loanId == l.id, { loanId = l.id; if (person.isBlank()) person = l.person }) {
                            InitialAvatar(l.person, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(l.person, fontWeight = FontWeight.SemiBold)
                                Text("Lent ${money(l.amount)} · ${dayLabel(l.date)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (left > 0) "${money(left)} left" else "settled", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            AnimatedVisibility(kind == Kind.LENT && initial.sms == null) {
                Column {
                    Label("Did you pay for them? Pick that payment")
                    Hint("Then it's counted once: your share stays in spending and their part shows as owed. Skip this for cash or a direct transfer.")
                    if (candidates.isEmpty()) Hint("No payments within a week of this date.")
                    candidates.forEach { p ->
                        val sel = parentId == p.id
                        PickCard(sel, {
                            parentId = if (sel) null else p.id
                            if (!sel && place.isBlank()) place = p.title
                        }) {
                            EmojiAvatar(p.emoji(), colorFor(p.category), 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${dayLabel(p.date)} · ${timeText(p)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(money(p.amount), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    byId[parentId]?.let { p ->
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Half" to 2, "⅓" to 3, "¼" to 4).forEach { (label, n) ->
                                AssistChip({ amount = plain(Math.round(p.amount / n * 100) / 100.0) }, label = { Text(label) })
                            }
                            AssistChip({ amount = plain(room(p)) }, label = { Text("Whole") })
                        }
                        Hint("Your share of ${money(p.amount)} at ${p.title}: ${money((room(p) - (value ?: 0.0)).coerceAtLeast(0.0))}")
                    }
                }
            }
            AnimatedVisibility(kind == Kind.REFUND) {
                Column {
                    Label("Which purchase was refunded?")
                    Hint("It comes off that purchase, so it isn't counted as spending. Leave it empty and it comes off this month's spending instead.")
                    if (refundable.isEmpty()) Hint("No purchases in the 60 days before this date.")
                    refundable.forEach { p ->
                        val sel = parentId == p.id
                        PickCard(sel, {
                            parentId = if (sel) null else p.id
                            if (!sel) { category = p.category; if (place.isBlank()) place = p.title }
                        }) {
                            EmojiAvatar(p.emoji(), colorFor(p.category), 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${dayLabel(p.date)} · ${timeText(p)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(money(p.amount), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        }
                    }
                    byId[parentId]?.let { p ->
                        val kept = p.amount - (value ?: 0.0)
                        if (kept > 0.005) Hint("${money(kept)} of ${money(p.amount)} wasn't refunded (fees) and still counts as spending.")
                    }
                }
            }
            if (initial.id != 0L && kind == Kind.SPENT && refunds.isNotEmpty()) {
                Label("🔙 Refunded")
                refunds.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(dayLabel(r.date), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("+" + money(r.amount), style = MaterialTheme.typography.bodyMedium, color = IncomeGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
                Hint("Counts as spending: ${money(((value ?: 0.0) - refunds.sumOf { it.amount } - kids.sumOf { it.amount }).coerceAtLeast(0.0))}")
            }
            if (kind == Kind.SPENT) {
                Label("🤝 Paid for friends too?")
                kids.forEach { k ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(k.person, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("owes ${money(k.amount)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                Hint(
                    if (kids.isEmpty()) "Split it equally with friends: only your share counts as spending, theirs goes to Lent."
                    else "Your share: ${money(((value ?: 0.0) - kids.sumOf { it.amount }).coerceAtLeast(0.0))}"
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton({ onSplit(result()) }, enabled = valid) { Text(if (kids.isEmpty()) "Split with friends" else "Change split") }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                person, { person = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(when (kind) { Kind.LENT -> "Lent to"; Kind.REPAY -> "Paid back by"; else -> "Person" }) },
                leadingIcon = { Icon(Icons.Rounded.Person, null) },
                isError = kind == Kind.LENT && person.isBlank(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(place, { place = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Place / merchant") }, leadingIcon = { Icon(Icons.Rounded.Place, null) })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note") })
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip({ pickDate = true }, label = { Text(fullDate(dt.toLocalDate())) }, leadingIcon = { Icon(Icons.Rounded.DateRange, null) })
                AssistChip({ pickTime = true }, label = { Text(timeText(initial.copy(time = time))) })
            }
            initial.sms?.let { body ->
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)) {
                    Text("📩  Original SMS", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (initial.id != 0L) TextButton({ onDelete(initial) }) {
                    Icon(Icons.Rounded.Delete, null, tint = SpendRed); Spacer(Modifier.width(6.dp)); Text("Delete", color = SpendRed)
                }
                Spacer(Modifier.weight(1f))
                Button(
                    {
                        onSave(result())
                    },
                    enabled = valid, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(18.dp),
                ) { Text(if (initial.id == 0L) "Add" else "Save", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp)) }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (pickDate) DatePick(dt.toLocalDate(), { pickDate = false }) { d ->
        time = LocalDateTime.of(d, dt.toLocalTime()).atZone(zone).toInstant().toEpochMilli()
    }
    if (pickTime) {
        val st = rememberTimePickerState(dt.hour, dt.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton({
                    time = dt.withHour(st.hour).withMinute(st.minute).atZone(zone).toInstant().toEpochMilli()
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ pickTime = false }) { Text("Cancel") } },
            text = { TimePicker(st) },
        )
    }
}

@Composable
private fun PickCard(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) = Surface(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, content = content) }

@Composable
private fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun CategoryTile(name: String, selected: Boolean, onClick: () -> Unit) {
    val c = colorFor(name)
    val scale by animateFloatAsState(if (selected) 1.06f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "tile")
    Column(
        Modifier.width(74.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .border(2.dp, if (selected) c else Color.Transparent, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(CATEGORIES[name] ?: "🏷️", fontSize = 24.sp)
        Spacer(Modifier.height(4.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
