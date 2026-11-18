package app.spendr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val openTxn = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openTxn.value = intent.txnId()
        setContent { SpendrTheme { App(openTxn) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openTxn.value = intent.txnId()
    }

    private fun Intent.txnId() = getLongExtra(EXTRA_TXN, 0L).takeIf { it != 0L }

    companion object { const val EXTRA_TXN = "txnId" }
}

private fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val tabs = listOf(Tab("Home", Icons.Rounded.Home), Tab("Activity", Icons.AutoMirrored.Rounded.List), Tab("Lent", Icons.Rounded.Face), Tab("Limits", Icons.Rounded.Settings))

@Composable
private fun App(openTxn: MutableState<Long?>) {
    val ctx = LocalContext.current
    val db = remember { Db.get(ctx) }
    val prefs = remember { ctx.getSharedPreferences("app", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current

    var txns by remember { mutableStateOf(emptyList<Txn>()) }
    var version by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf(Filter()) }
    var editing by remember { mutableStateOf<Txn?>(null) }
    var splitting by remember { mutableStateOf<Txn?>(null) }
    var applyAll by remember { mutableStateOf<Pair<Txn, Int>?>(null) } // saved txn + how many others share its payee
    var importing by remember { mutableStateOf(false) }
    var hasSms by remember { mutableStateOf(granted(ctx, Manifest.permission.READ_SMS)) }

    LaunchedEffect(version) { txns = withContext(Dispatchers.IO) { db.all() } }

    fun toast(msg: String, action: String? = null, onAction: () -> Unit = {}) = scope.launch {
        snack.currentSnackbarData?.dismiss()
        if (snack.showSnackbar(msg, action, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onAction()
    }

    /** Runs a DB write off the main thread, then refreshes the UI. */
    fun write(block: () -> Unit, then: () -> Unit = {}) = scope.launch {
        withContext(Dispatchers.IO) { block(); Alerts.check(ctx) }
        version++
        then()
    }

    fun scan(full: Boolean) {
        if (importing) return
        importing = true
        scope.launch {
            val started = System.currentTimeMillis()
            // new parsing rules need old SMS again (fund confirmations from months back), so rescan everything once
            val newRules = prefs.getInt("rules", 0) < Sms.RULES && prefs.getLong("lastScan", 0L) > 0
            // a day of overlap is free: duplicates are dropped by the SMS key
            val since = if (full || newRules) 0L else (prefs.getLong("lastScan", 0L) - 86_400_000L).coerceAtLeast(0L)
            val n = runCatching { withContext(Dispatchers.IO) { Sms.importInbox(ctx, since).also { Alerts.check(ctx) } } }.getOrDefault(-1)
            if (n >= 0) prefs.edit { putLong("lastScan", started); putInt("rules", Sms.RULES) }
            importing = false
            version++
            when {
                n < 0 -> if (full) toast("Couldn't read your SMS — check the permission")
                newRules -> toast("🧹 Re-sorted your transactions with smarter rules")
                full || n > 0 -> toast(if (n > 0) "✨ Found $n new transaction${if (n == 1) "" else "s"}" else "All caught up — no new bank SMS")
            }
        }
    }

    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasSms = granted(ctx, Manifest.permission.READ_SMS)
        if (hasSms) scan(full = true)
    }
    val enableSms = {
        askPerms.launch(
            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS) +
                if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        )
    }

    LifecycleResumeEffect(Unit) {
        hasSms = granted(ctx, Manifest.permission.READ_SMS)
        if (hasSms) scan(full = false) else version++
        onPauseOrDispose { }
    }

    // opened from a "just tracked" notification → jump straight into that transaction
    LaunchedEffect(openTxn.value, txns) {
        val id = openTxn.value ?: return@LaunchedEffect
        txns.firstOrNull { it.id == id }?.let { editing = it; openTxn.value = null }
    }

    BackHandler(tab != 0) { tab = 0 }

    val save: (Txn) -> Unit = { t ->
        editing = null
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        // changed the icon (type/category)? count this payee's other transactions to offer the same change
        val before = txns.firstOrNull { it.id == t.id }
        val key = t.payeeKey()
        val others = if (before == null || key == null || t.kind !in RULE_KINDS || (before.kind == t.kind && before.category == t.category)) 0
        else txns.count { it.id != t.id && it.kind in RULE_KINDS && it.payeeKey() == key && (it.kind != t.kind || it.category != t.category) }
        write({ db.save(t) }) {
            if (others > 0) applyAll = t to others else toast(
                when {
                    t.id == 0L && t.kind == Kind.LENT -> "🤝 ${t.person} owes you ${money(t.amount)}"
                    t.id == 0L -> "Added ${money(t.amount)} ✅"
                    else -> "Saved ✅"
                }
            )
        }
    }
    // save the payment as edited (a new one gets its id here), then open the split sheet for it
    val split: (Txn) -> Unit = { parent ->
        editing = null
        scope.launch {
            val id = withContext(Dispatchers.IO) { db.save(parent) }
            version++
            splitting = parent.copy(id = id)
        }
    }
    val applySplit: (Txn, Split) -> Unit = { parent, s ->
        splitting = null
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        write({ db.setSplit(parent, s.shares) }) {
            val names = s.shares.keys.toList()
            toast(
                when {
                    names.isEmpty() -> "Split removed — the whole ${money(parent.amount)} is yours again"
                    names.size == 1 -> "🤝 ${names[0]} owes you ${money(s.each)}"
                    else -> "🤝 Split with ${names.dropLast(1).joinToString(", ")} & ${names.last()} · ${money(s.each)} each"
                }
            )
        }
    }
    // a payment that was marked Lent but was really yours: back to spending, out of the Lent tab
    val backToSpending: (Txn) -> Unit = { t ->
        write({ db.save(t.copy(kind = Kind.SPENT, parentId = null)) }) {
            toast("Moved ${money(t.amount)} back to your spending", "Undo") { write({ db.save(t) }) }
        }
    }
    val delete: (Txn) -> Unit = { t ->
        editing = null
        write({ db.delete(t) }) { toast("Deleted ${money(t.amount)} · ${t.title}", "Undo") { write({ db.restore(t) }) } }
    }
    val repay: (Loan, Double) -> Unit = { loan, amt ->
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        val name = loan.lent.person.ifBlank { "Your friend" }
        write({ db.save(Txn(amount = amt, isOut = false, kind = Kind.REPAY, person = loan.lent.person, loanId = loan.lent.id, note = "Paid back")) }) {
            toast(if (amt >= loan.left - 0.009) "🎉 $name paid you back in full!" else "Got it! ${money(loan.left - amt)} still pending from $name")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(tab == i, { tab = i }, icon = { Icon(t.icon, t.label) }, label = { Text(t.label) })
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(tab != 3, enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = { editing = if (tab == 2) Txn(kind = Kind.LENT) else Txn() },
                    icon = { Icon(Icons.Rounded.Add, null) },
                    text = { Text(if (tab == 2) "Lend" else "Add") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(20.dp),
                )
            }
        },
    ) { pad ->
        AnimatedContent(
            tab, Modifier.padding(pad).consumeWindowInsets(pad),
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (fadeIn(tween(260)) + slideInHorizontally(tween(320)) { it / 6 * dir }) togetherWith
                    (fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { -it / 6 * dir })
            },
            label = "tabs",
        ) { t ->
            when (t) {
                0 -> HomeScreen(
                    txns, hasSms, enableSms, onOpen = { editing = it },
                    onCategory = { filter = Filter(type = Kind.SPENT.label, category = it); tab = 1 },
                    onSeeAll = { filter = Filter(); tab = 1 }, onLimits = { tab = 3 }, onLent = { tab = 2 },
                )
                1 -> TxnScreen(txns, filter, { filter = it }, { editing = it }, delete)
                2 -> LentScreen(txns, { editing = it }, repay, delete, backToSpending)
                else -> SettingsScreen(
                    txns, hasSms, importing, enableSms, { scan(full = true) },
                    // the rent rule may re-file past payments, so re-sort the SMS ones too
                    onSaveLimits = { write({ Limits(ctx).applyRentRule(); db.reclassify() }) { toast("Limits saved 🎯") } },
                    onMessage = { toast(it) },
                )
            }
        }
    }

    editing?.let { EditSheet(it, txns, { editing = null }, save, delete, split) }
    splitting?.let { p -> SplitSheet(p, txns, { splitting = null }) { applySplit(p, it) } }
    applyAll?.let { (t, n) ->
        val what = if (t.kind == Kind.SPENT) "${t.emoji()} ${t.category}" else "${t.kind.emoji} ${t.kind.label}"
        AlertDialog(
            onDismissRequest = { applyAll = null; toast("Saved just this one ✅") },
            title = { Text("Apply to all from ${t.title}?") },
            text = { Text("Make the other $n transaction${if (n == 1) "" else "s"} with ${t.title} $what too, and new ones from them as well?") },
            confirmButton = {
                Button({
                    applyAll = null
                    var changed = 0
                    write({ changed = db.applyToPayee(t) }) { toast("$what applied to $changed more from ${t.title} ✅") }
                }) { Text("Apply to all") }
            },
            dismissButton = { TextButton({ applyAll = null; toast("Saved just this one ✅") }) { Text("Just this one") } },
        )
    }
}
