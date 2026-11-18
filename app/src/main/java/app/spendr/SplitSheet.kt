package app.spendr

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/** Split a payment equally with friends: each friend's share becomes a loan in Lent, linked to the payment. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SplitSheet(parent: Txn, txns: List<Txn>, onDismiss: () -> Unit, onSplit: (Split) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("app", Context.MODE_PRIVATE) }
    val existing = remember(txns) { txns.filter { it.kind == Kind.LENT && it.parentId == parent.id && parent.id != 0L } }
    // a friend who has already paid something back on this bill stays in the split
    val locked = remember(txns) { existing.filter { e -> txns.any { it.kind == Kind.REPAY && it.loanId == e.id } }.map { it.person.lowercase() }.toSet() }
    var friends by remember { mutableStateOf(knownFriends(txns, prefs.getStringSet("friends", emptySet()).orEmpty())) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(existing.map { it.person }) } }
    var includeMe by remember { mutableStateOf(existing.isEmpty() || existing.sumOf { it.amount } < parent.amount - 0.009) }
    var newName by remember { mutableStateOf("") }
    val split = equalSplit(parent.amount, selected.toList(), includeMe)

    fun isSelected(name: String) = selected.any { it.equals(name, true) }
    fun addFriend() {
        val name = newName.trim().replace(Regex("\\s+"), " ")
        if (name.isEmpty()) return
        if (friends.none { it.equals(name, true) }) friends = listOf(name) + friends
        if (!isSelected(name)) selected += name
        prefs.edit { putStringSet("friends", prefs.getStringSet("friends", emptySet()).orEmpty() + name) }
        newName = ""
    }

    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().imePadding()) {
            Text("Split equally", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Text(
                "${money(parent.amount)} · ${parent.title} · ${dayLabel(parent.date)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Who was in?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            if (friends.isEmpty()) {
                Text("Add the friends you're splitting with — they're remembered for next time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.animateContentSize()) {
                friends.forEach { f ->
                    val sel = isSelected(f)
                    FilterChip(
                        sel,
                        { if (!sel) selected += f else if (f.lowercase() !in locked) selected.removeAll { it.equals(f, true) } },
                        label = { Text(f) },
                        leadingIcon = { if (sel) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) else InitialAvatar(f, 22.dp) },
                    )
                }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    newName, { newName = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Add a friend") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addFriend() }),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton(::addFriend, enabled = newName.isNotBlank()) { Icon(Icons.Rounded.Add, "Add friend") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include me", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text("Turn off if you only paid for them", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(includeMe, { includeMe = it })
            }
            AnimatedVisibility(selected.isNotEmpty()) {
                Column(
                    Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)).padding(16.dp).animateContentSize()
                ) {
                    Text("Each friend owes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AnimatedMoney(split.each, MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.ExtraBold), color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    split.shares.forEach { (name, amt) ->
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            InitialAvatar(name, 28.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(money(amt), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your share: ${money(split.mine)} — only this counts as your spending",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Button(
                { onSplit(split) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp),
                enabled = selected.isNotEmpty() || existing.isNotEmpty(),
            ) {
                Text(
                    when {
                        selected.isEmpty() -> "Remove split"
                        else -> "Split · ${money(split.each)} each"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
