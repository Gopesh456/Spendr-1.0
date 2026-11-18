package app.spendr

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun SettingsScreen(
    txns: List<Txn>,
    hasSms: Boolean,
    importing: Boolean,
    onEnableSms: () -> Unit,
    onScan: () -> Unit,
    onSaveLimits: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val lim = remember { Limits(ctx) }
    val cats = remember(txns) {
        CATEGORIES.keys.filter { it != "Rent" } + txns.filter { it.countsAsSpending() }.map { it.category }.filter { it.isNotBlank() && it !in CATEGORIES }.distinct()
    }
    var daily by remember { mutableStateOf(lim.daily.takeIf { it > 0 }?.let(::plain).orEmpty()) }
    var monthly by remember { mutableStateOf(lim.monthly.takeIf { it > 0 }?.let(::plain).orEmpty()) }
    val catLimits = remember { mutableStateMapOf<String, String>() }
    var rentPayee by remember { mutableStateOf(lim.rentPayee) }
    var rentMin by remember { mutableStateOf(plain(lim.rentMin)) }
    var notifOk by remember { mutableStateOf(notificationsAllowed(ctx)) }
    val notifAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = it }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(csv(txns).toByteArray()) } }.isSuccess
            onMessage(if (ok) "📤 Exported ${txns.size} transactions" else "Export failed — try another folder")
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(Modifier.padding(top = 8.dp, start = 4.dp).enter(0)) {
                Text("Limits & settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("You'll get a ping at 80% and again at 100% of any limit.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            CardBox(Modifier.enter(1)) {
                Column {
                    Text("Overall", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MoneyField("Daily", daily, Modifier.weight(1f)) { daily = it }
                        MoneyField("Monthly", monthly, Modifier.weight(1f)) { monthly = it }
                    }
                }
            }
        }
        item {
            CardBox(Modifier.enter(2)) {
                Column {
                    Text("Per category (monthly)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    cats.forEach { c ->
                        val v = catLimits[c] ?: lim.category(c).takeIf { it > 0 }?.let(::plain).orEmpty()
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            EmojiAvatar(CATEGORIES[c] ?: "🏷️", colorFor(c), 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(c, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            MoneyField("No limit", v, Modifier.width(140.dp), placeholder = true) { catLimits[c] = it }
                        }
                    }
                }
            }
        }
        item {
            CardBox(Modifier.enter(3)) {
                Column {
                    Text("🏠  Rent rule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Payments to this person above the amount are filed as Rent, which isn't counted as spending.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(rentPayee, { rentPayee = it }, Modifier.weight(1.4f), label = { Text("Paid to (name)") }, placeholder = { Text("Off") }, singleLine = true, shape = MaterialTheme.shapes.medium)
                        MoneyField("Above", rentMin, Modifier.weight(1f)) { rentMin = it }
                    }
                }
            }
        }
        item {
            Button({
                lim.rentPayee = rentPayee
                lim.rentMin = rentMin.toDoubleOrNull() ?: 30_000.0
                lim.daily = daily.toDoubleOrNull() ?: 0.0
                lim.monthly = monthly.toDoubleOrNull() ?: 0.0
                catLimits.forEach { (c, v) -> lim.setCategory(c, v.toDoubleOrNull() ?: 0.0) }
                onSaveLimits()
            }, Modifier.fillMaxWidth().height(54.dp).enter(3)) { Text("Save limits", fontWeight = FontWeight.Bold) }
        }
        item {
            CardBox(Modifier.enter(4)) {
                Column {
                    Text("📩  SMS tracking", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (hasSms) "On. New bank & UPI messages are logged automatically. Card bill payments and transfers to yourself are marked Excluded so nothing is counted twice."
                        else "Off. Turn it on to log payments from your bank SMS automatically.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (!hasSms) Button(onEnableSms) { Text("Enable SMS tracking") }
                    else FilledTonalButton(onScan, enabled = !importing) {
                        AnimatedContent(importing, label = "scan") { busy ->
                            if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp)); Text("Scanning…")
                            } else Text("Rescan whole inbox")
                        }
                    }
                }
            }
        }
        if (!notifOk && Build.VERSION.SDK_INT >= 33) item {
            CardBox(Modifier.enter(5)) {
                Column {
                    Text("🔔  Notifications are off", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Without them Spendr can't warn you before you cross a limit.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button({ notifAsk.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
                }
            }
        }
        item {
            CardBox(Modifier.enter(6)) {
                Column {
                    Text("📤  Backup & export", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Everything lives only on this phone. Export a CSV now and then — it opens in Excel or Google Sheets.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton({ export.launch("spendr-${LocalDate.now()}.csv") }, enabled = txns.isNotEmpty()) { Text("Export CSV") }
                }
            }
        }
        item {
            Text(
                "Spendr ${remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }} · made just for you 💜", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun MoneyField(label: String, value: String, modifier: Modifier, placeholder: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { s -> onChange(s.filter { it.isDigit() || it == '.' }) }, modifier,
        label = if (placeholder) null else ({ Text(label) }),
        placeholder = if (placeholder) ({ Text(label) }) else null,
        prefix = { Text("₹") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = MaterialTheme.shapes.medium,
    )
}

fun notificationsAllowed(ctx: android.content.Context) = Build.VERSION.SDK_INT < 33 ||
    ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
