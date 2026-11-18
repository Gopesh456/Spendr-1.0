package app.spendr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TxnScreen(txns: List<Txn>, filter: Filter, onFilter: (Filter) -> Unit, onOpen: (Txn) -> Unit, onDelete: (Txn) -> Unit) {
    // a loan split out of a payment shows inside that payment, except when you ask for Lent specifically
    val shown = remember(txns, filter) { (if (filter.type == Kind.LENT.label) txns else txns.forActivity()).filter(filter::matches) }
    val shares = remember(txns) { Shares(txns) }
    var sheet by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Transactions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp, start = 4.dp))
            OutlinedTextField(
                filter.query, { onFilter(filter.copy(query = it)) }, Modifier.fillMaxWidth(),
                placeholder = { Text("Search person, place, note…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (filter.query.isNotEmpty()) IconButton({ onFilter(filter.copy(query = "")) }) { Icon(Icons.Rounded.Close, "Clear") } },
                singleLine = true, shape = CircleShape,
            )
            MonthBar(filter, onFilter)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(
                    filter.extraCount > 0, { sheet = true },
                    label = { Text(if (filter.extraCount > 0) "Filters · ${filter.extraCount}" else "More filters") },
                )
            }
            if (filter.extraCount > 0) item {
                AssistChip({ onFilter(filter.copy(day = null, person = "", place = "", category = "", min = null, max = null)) }, label = { Text("Clear") }, leadingIcon = { Icon(Icons.Rounded.Close, null) })
            }
            items(TYPES) { t ->
                FilterChip(filter.type == t, { onFilter(filter.copy(type = t)) }, label = { Text(t) })
            }
        }
        Summary(shown, shares, filter.type)
        if (shown.isEmpty()) {
            EmptyState("🔍", "Nothing here", if (txns.isEmpty()) "Add your first transaction with +" else "Try another month or loosen the filters.")
        } else LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 110.dp), modifier = Modifier.fillMaxSize()) {
            shown.groupBy { it.date }.forEach { (day, list) ->
                stickyHeader(key = "h$day") { DayHeader(day, list, shares) }
                items(list, key = { it.id }) { t -> SwipeRow(t, shares.lent[t.id] ?: 0.0, shares.refunded[t.id] ?: 0.0, Modifier.animateItem(), { onOpen(t) }, { onDelete(t) }) }
            }
        }
    }
    if (sheet) FilterSheet(filter, { sheet = false }, { onFilter(it); sheet = false })
}

@Composable
private fun MonthBar(filter: Filter, onFilter: (Filter) -> Unit) {
    val now = YearMonth.now()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val m = filter.month
        IconButton({ onFilter(filter.copy(month = (m ?: now).minusMonths(1), day = null)) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month") }
        AnimatedContent(
            targetState = filter.day to m,
            transitionSpec = {
                val fwd = (targetState.second ?: YearMonth.of(9999, 1)) >= (initialState.second ?: YearMonth.of(9999, 1))
                (slideInHorizontally { if (fwd) it / 2 else -it / 2 } + fadeIn()) togetherWith (slideOutHorizontally { if (fwd) -it / 2 else it / 2 } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "month",
        ) { (d, mm) ->
            Text(
                when { d != null -> dayLabel(d); mm == null -> "All time"; else -> mm.format(monthFmt) },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton({ onFilter(filter.copy(month = (m ?: now).plusMonths(1), day = null)) }, enabled = m != null && m < now) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month")
        }
        FilterChip(m == null && filter.day == null, { onFilter(filter.copy(month = if (m == null) now else null, day = null)) }, label = { Text("All time") })
    }
}

@Composable
private fun Summary(shown: List<Txn>, shares: Shares, type: String) {
    // "Spent" is your share only (split bills, refunds and rent taken out) — the same number Home shows
    val spent = shown.sumOf(shares::spending).coerceAtLeast(0.0)
    val received = shown.filter { it.kind == Kind.RECEIVED || it.kind == Kind.REPAY }.sumOf { it.amount }
    Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val w = Modifier.weight(1f)
        when (type) {
            "All" -> { SummaryTile("Spent", spent, SpendRed, w); SummaryTile("Received", received, IncomeGreen, w) }
            "Paid", Kind.SPENT.label -> SummaryTile("Spent", spent, SpendRed, w)
            "Received" -> SummaryTile("Received", received, IncomeGreen, w)
            else -> {
                val kind = Kind.entries.firstOrNull { it.label == type }
                SummaryTile(type, shown.sumOf { it.amount }, if (kind?.out == false) IncomeGreen else SpendRed, w)
            }
        }
    }
}

@Composable
private fun SummaryTile(label: String, v: Double, c: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c, fontWeight = FontWeight.Bold)
        AnimatedMoney(v, MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold))
    }
}

@Composable
private fun DayHeader(day: LocalDate, list: List<Txn>, shares: Shares) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dayLabel(day), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        val out = list.sumOf(shares::spending) // your spending that day
        if (out > 0) Text("−" + money(out), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(t: Txn, lent: Double, refunded: Double, modifier: Modifier, onOpen: () -> Unit, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    // The list remembers swipe state per row id, so a row brought back by Undo arrives "already swiped away".
    // Slide it back in instead of deleting it again: only a fresh swipe on a settled row deletes.
    var armed by remember { mutableStateOf(state.currentValue == SwipeToDismissBoxValue.Settled) }
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.Settled -> armed = true
            SwipeToDismissBoxValue.EndToStart -> if (armed) { armed = false; onDelete() } else state.reset()
            else -> {}
        }
    }
    SwipeToDismissBox(
        state, modifier = modifier, enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)).background(SpendRed).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Icon(Icons.Rounded.Delete, "Delete", tint = Color.White) }
        },
    ) {
        Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(18.dp)) { TxnRow(t, lent = lent, refunded = refunded, onClick = onOpen) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(initial: Filter, onDismiss: () -> Unit, onApply: (Filter) -> Unit) {
    var person by remember { mutableStateOf(initial.person) }
    var place by remember { mutableStateOf(initial.place) }
    var category by remember { mutableStateOf(initial.category) }
    var min by remember { mutableStateOf(initial.min?.let(::plain).orEmpty()) }
    var max by remember { mutableStateOf(initial.max?.let(::plain).orEmpty()) }
    var day by remember { mutableStateOf(initial.day) }
    var pickDay by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Filters", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(person, { person = it }, Modifier.fillMaxWidth(), label = { Text("Person") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(place, { place = it }, Modifier.fillMaxWidth(), label = { Text("Place / merchant") }, singleLine = true)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(min, { min = it }, Modifier.weight(1f), label = { Text("Min ₹") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(max, { max = it }, Modifier.weight(1f), label = { Text("Max ₹") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Spacer(Modifier.height(16.dp))
            Text("Exact date", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip({ pickDay = true }, label = { Text(day?.let(::fullDate) ?: "Any day") }, leadingIcon = { Icon(Icons.Rounded.DateRange, null) })
                AnimatedVisibility(day != null) { IconButton({ day = null }) { Icon(Icons.Rounded.Close, "Clear date") } }
            }
            Spacer(Modifier.height(12.dp))
            Text("Category", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CATEGORIES.forEach { (c, e) -> FilterChip(category == c, { category = if (category == c) "" else c }, label = { Text("$e $c") }) }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ onApply(initial.copy(day = null, person = "", place = "", category = "", min = null, max = null)) }, Modifier.weight(1f)) { Text("Reset") }
                Button({
                    onApply(initial.copy(person = person, place = place, category = category, min = min.toDoubleOrNull(), max = max.toDoubleOrNull(), day = day))
                }, Modifier.weight(1f)) { Text("Show results") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (pickDay) DatePick(day ?: LocalDate.now(), { pickDay = false }) { day = it }
}

/** 1500.0 -> "1500", 12.5 -> "12.5" for editable fields. */
fun plain(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
