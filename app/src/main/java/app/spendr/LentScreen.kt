package app.spendr

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun LentScreen(txns: List<Txn>, onOpen: (Txn) -> Unit, onRepay: (Loan, Double) -> Unit, onDelete: (Txn) -> Unit, onSpending: (Txn) -> Unit) {
    val loans = remember(txns) { openLoans(txns) }
    var repayFor by remember { mutableStateOf<Loan?>(null) }
    var askDelete by remember { mutableStateOf<Txn?>(null) }
    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Lent money", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 8.dp, start = 4.dp).enter(0))
        }
        item {
            Box(Modifier.enter(1).fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(MintBrush).padding(22.dp)) {
                Column {
                    Text("Friends owe you", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall)
                    AnimatedMoney(loans.sumOf { it.left }, MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.ExtraBold), color = Color.White)
                    val people = loans.map { it.lent.person.lowercase() }.distinct().size
                    Text(
                        if (loans.isEmpty()) "Everyone's settled up ✨" else "$people ${if (people == 1) "person" else "people"} · ${loans.size} open ${if (loans.size == 1) "loan" else "loans"}",
                        color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (loans.isEmpty()) item {
            EmptyState(
                "🤝", "Nobody owes you anything",
                "Open any payment and set its type to Lent, or tap Lend. When a friend pays you back it shows here until it's fully settled.",
            )
        }
        items(loans, key = { it.lent.id }) { loan ->
            // a loan that IS a bank payment would vanish from your history if deleted, so ask what it really was
            LoanCard(loan, Modifier.animateItem(), onOpen, { if (loan.lent.sms != null) askDelete = loan.lent else onDelete(loan.lent) }) { repayFor = loan }
        }
    }
    repayFor?.let { loan -> RepayDialog(loan, { repayFor = null }) { onRepay(loan, it); repayFor = null } }
    askDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            icon = { Icon(Icons.Rounded.Delete, null) },
            title = { Text("Remove from Lent?") },
            text = {
                Text(
                    "This ${money(t.amount)} payment${t.person.takeIf { it.isNotBlank() }?.let { " to $it" }.orEmpty()} came from your bank SMS. " +
                        "If it wasn't a loan, keep it as your own spending. Delete only if it shouldn't be tracked at all."
                )
            },
            confirmButton = { Button({ askDelete = null; onSpending(t) }) { Text("It was my spending") } },
            dismissButton = {
                Row {
                    TextButton({ askDelete = null }) { Text("Cancel") }
                    TextButton({ askDelete = null; onDelete(t) }) { Text("Delete", color = SpendRed) }
                }
            },
        )
    }
}

@Composable
private fun LoanCard(loan: Loan, modifier: Modifier, onOpen: (Txn) -> Unit, onDelete: () -> Unit, onRepay: () -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val l = loan.lent
    CardBox(modifier.animateContentSize()) {
        Column {
            Row(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(l) }, verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(l.person)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.person.ifBlank { "Someone" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(listOf("Lent ${money(l.amount)}", l.place, dayLabel(l.date)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedMoney(loan.left, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.primary)
                    Text("pending", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            LimitBar((loan.paid / l.amount).toFloat(), ok = IncomeGreen, colorize = false, height = 8.dp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (loan.paid > 0) "Paid back ${money(loan.paid)} of ${money(l.amount)} so far" else "Nothing paid back yet",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                )
                // lives up here, not in the button row, so the buttons always fit on one line
                if (loan.repayments.isNotEmpty()) TextButton({ expanded = !expanded }) { Text(if (expanded) "Hide" else "History (${loan.repayments.size})") }
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    loan.repayments.forEach { r ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpen(r) }.padding(vertical = 6.dp, horizontal = 4.dp)) {
                            Text("↩️  ${dayLabel(r.date)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("+" + money(r.amount), color = IncomeGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onDelete) { Icon(Icons.Rounded.Delete, "Delete loan", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.weight(1f))
                OutlinedButton({
                    val name = l.person.trim().substringBefore(' ').ifBlank { "there" }
                    val text = "Hey $name! Gentle reminder about the ${money(loan.left)} from ${fullDate(l.date)}" +
                        (if (loan.paid > 0) " (you've already sent ${money(loan.paid)} of ${money(l.amount)}, thanks!)" else "") +
                        ". UPI me whenever you get a chance 🙂"
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send reminder"))
                }) { Text("Remind", maxLines = 1, softWrap = false) }
                FilledTonalButton(onRepay) { Text("Got paid", maxLines = 1, softWrap = false) }
            }
        }
    }
}

@Composable
private fun RepayDialog(loan: Loan, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var amount by remember { mutableStateOf(plain(loan.left)) }
    val v = amount.toDoubleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Payment from ${loan.lent.person.ifBlank { "friend" }}") },
        text = {
            Column {
                OutlinedTextField(
                    amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Amount received") }, prefix = { Text("₹") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ amount = plain(loan.left) }, label = { Text("Full") })
                    AssistChip({ amount = plain(Math.round(loan.left / 2 * 100) / 100.0) }, label = { Text("Half") })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Came in by UPI and already in your list? Open that transaction and set its type to Repayment instead, so it isn't counted twice.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button({ v?.let(onConfirm) }, enabled = v != null && v > 0) { Text("Record") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
