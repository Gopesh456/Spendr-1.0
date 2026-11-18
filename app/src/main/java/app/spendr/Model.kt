package app.spendr

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/** [out]: true = money leaves you, false = money comes in, null = either way. */
enum class Kind(val label: String, val emoji: String, val out: Boolean?) {
    SPENT("Spending", "💸", true),
    INVEST("Invested", "📈", true),
    LENT("Lent", "🤝", true),
    RECEIVED("Received", "💰", false),
    REPAY("Repayment", "↩️", false),
    REFUND("Refund", "🔙", false),
    EXCLUDED("Excluded", "🔁", null),
}

data class Txn(
    val id: Long = 0,
    val amount: Double = 0.0,
    val isOut: Boolean = true,
    val kind: Kind = Kind.SPENT,
    val category: String = "Other",
    val person: String = "",
    val place: String = "",
    val note: String = "",
    val time: Long = System.currentTimeMillis(),
    val sms: String? = null,
    val smsKey: String? = null,
    val loanId: Long? = null, // for REPAY: id of the LENT txn it pays back
    // LENT: the payment it was carved out of (you paid, a friend owes their part). REFUND: the purchase it gives back.
    val parentId: Long? = null,
    val edited: Boolean = false, // touched by the user: automatic rules leave it alone
) {
    val date: LocalDate get() = time.toDate()
    val title: String
        get() = place.ifBlank { person }.ifBlank { if (kind == Kind.SPENT) category else kind.label }
}

val CATEGORIES = linkedMapOf(
    "Food" to "🍔", "Groceries" to "🛒", "Transport" to "🚕", "Fuel" to "⛽",
    "Shopping" to "🛍️", "Bills" to "💡", "Entertainment" to "🎬", "Health" to "💊",
    "Travel" to "✈️", "Rent" to "🏠", "Education" to "📚", "Cash" to "💵", "Other" to "💸",
)

/** Types a "same payee" rule can set; loans, repayments and refunds are about one payment, not the person. */
val RULE_KINDS = setOf(Kind.SPENT, Kind.INVEST, Kind.EXCLUDED, Kind.RECEIVED)

/** Who a transaction is with, per direction ("out:ramesh kumar"); null when the SMS named nobody. */
fun Txn.payeeKey(): String? =
    place.ifBlank { person }.trim().lowercase().takeIf { it.isNotEmpty() }?.let { (if (isOut) "out:" else "in:") + it }

/** "My spending": what totals, limits and charts count. Rent is tracked but deliberately left out. */
fun Txn.countsAsSpending() = kind == Kind.SPENT && category != "Rent"

fun Txn.emoji() = if (kind == Kind.SPENT) CATEGORIES[category] ?: "🏷️" else kind.emoji

fun Long.toDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

fun money(v: Double): String =
    "₹" + NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"))
        .apply { maximumFractionDigits = 2; minimumFractionDigits = if (Math.round(v * 100) % 100 == 0L) 0 else 2 }.format(v)

data class Loan(val lent: Txn, val repayments: List<Txn>) {
    val paid = repayments.sumOf { it.amount }
    val left = lent.amount - paid
}

/** Loans not yet fully paid back, oldest first. */
fun openLoans(all: List<Txn>): List<Loan> {
    val repays = all.filter { it.kind == Kind.REPAY && it.loanId != null }.groupBy { it.loanId }
    return all.filter { it.kind == Kind.LENT }
        .map { Loan(it, repays[it.id].orEmpty().sortedBy { r -> r.time }) }
        .filter { it.left > 0.009 }
        .sortedBy { it.lent.time }
}

private fun byParent(all: List<Txn>, kind: Kind): Map<Long, Double> =
    all.filter { it.kind == kind && it.parentId != null }.groupBy { it.parentId!! }.mapValues { (_, l) -> l.sumOf { it.amount } }

/** How much of each payment friends owe you (loans split out of it), by payment id. */
fun lentByParent(all: List<Txn>) = byParent(all, Kind.LENT)

/** How much of each purchase came back as refunds, by purchase id. */
fun refundByParent(all: List<Txn>) = byParent(all, Kind.REFUND)

data class Split(val each: Double, val mine: Double, val shares: Map<String, Double>)

/** Splits [total] equally between [friends] (and you, if [includeMe]); you absorb the leftover paise. */
fun equalSplit(total: Double, friends: List<String>, includeMe: Boolean): Split {
    if (friends.isEmpty()) return Split(0.0, total, emptyMap())
    val paise = Math.round(total * 100)
    val each = paise / (friends.size + if (includeMe) 1 else 0) // whole paise, in Long so ₹0.29 doesn't become ₹0.28
    return Split(each / 100.0, (paise - each * friends.size) / 100.0, friends.associateWith { each / 100.0 })
}

/** Friends you've lent to or been repaid by (most recent first), then ones you added by hand. */
fun knownFriends(all: List<Txn>, saved: Set<String>): List<String> =
    (all.filter { (it.kind == Kind.LENT || it.kind == Kind.REPAY) && it.person.isNotBlank() }.sortedByDescending { it.time }.map { it.person.trim() } + saved.sorted())
        .distinctBy { it.lowercase() }

/**
 * What the transaction lists show. Loans you added by hand (Lend, or a friend's part of a split bill) aren't bank
 * transactions: they live in the Lent tab, and a split part shows inside its payment as "₹X lent".
 * A bank payment you marked as Lent is real money out, so it stays.
 */
fun List<Txn>.forActivity(): List<Txn> = filter { it.kind != Kind.LENT || it.sms != null }

private const val DAY = 86_400_000L

/** Shops keep convenience / delivery fees, so a refund can come back up to this much short of the purchase. */
const val REFUND_FEE_WINDOW = 50.0

private val ONLINE = Regex(
    """(?i)\b(amazon|flipkart|myntra|ajio|meesho|nykaa|swiggy|zomato|blinkit|zepto|bigbasket|instamart|jiomart|irctc|railways|makemytrip|""" +
        """goibibo|cleartrip|redbus|ixigo|confirmtkt|bookmyshow|district|tata cliq|croma|ekart|uber|ola|rapido|pharmeasy|1mg|netmeds|lenskart|""" +
        """decathlon|firstcry|purplle|snapdeal|shopsy)\b"""
)

/**
 * Links refunds to the purchases they give back, so that purchase stops counting as spending.
 * Only money that provably comes from the shop is a refund — never a credit guessed from its amount alone, since
 * friends' UPI payments arrive as the same bare "A/C credited with INR X" SMS:
 * - a Refund: a bank "refund / reversed" SMS, or a credit matched to the merchant's own refund notice;
 * - a credit whose sender is an online shop ("... credited by AMAZON").
 * Each goes to the purchase from the same merchant it "fits" (same amount or up to [REFUND_FEE_WINDOW] less for fees
 * the shop keeps; closest fit wins), else that merchant's latest purchase (partial refund). A Refund SMS that names
 * no matching merchant may also take a fitting purchase in its category, or an exact one (UPI reversal).
 * Only rows where [auto] is true are changed; returns the changed rows.
 */
fun linkRefunds(all: List<Txn>, auto: (Txn) -> Boolean): List<Txn> {
    val purchases = all.filter { it.kind == Kind.SPENT && it.isOut }.sortedByDescending { it.time }
    val used = HashMap<Long, Double>()
    all.filter { it.kind == Kind.REFUND && it.parentId != null && !auto(it) }.forEach { used.merge(it.parentId!!, it.amount, Double::plus) }
    fun words(t: Txn) = "${t.place} ${t.person}".lowercase().split(Regex("[^a-z]+")).filter { it.length >= 4 }.toSet()
    val changed = mutableListOf<Txn>()
    for (r in all.filter { !it.isOut && auto(it) }.sortedBy { it.time }) {
        val fromShop = r.kind == Kind.RECEIVED && ONLINE.containsMatchIn("${r.place} ${r.person}")
        if (r.kind != Kind.REFUND && !fromShop) continue
        val earlier = purchases.filter { it.time < r.time && r.time - it.time <= 60 * DAY && it.amount - (used[it.id] ?: 0.0) >= r.amount - 0.005 }
        fun exact(p: Txn) = kotlin.math.abs(p.amount - r.amount) < 0.01
        fun fits(p: Txn) = r.amount <= p.amount + 0.005 && r.amount >= p.amount - REFUND_FEE_WINDOW
        fun best(list: List<Txn>) = list.minWithOrNull(compareBy<Txn>({ it.amount - r.amount }, { -it.time }))
        val rw = words(r)
        val same = earlier.filter { p -> words(p).any { it in rw } }
        val p = best(same.filter(::fits)) ?: same.firstOrNull() ?: if (r.kind != Kind.REFUND) null else
            best(earlier.filter { r.category != "Other" && it.category == r.category && fits(it) })
                ?: best(earlier.filter { r.time - it.time <= 30 * DAY && exact(it) }) // e.g. a UPI reversal
        val n = if (p == null) r.copy(kind = Kind.REFUND, parentId = null, category = r.category.takeIf { it != "Other" } ?: SmsParser.categoryOf("${r.place} ${r.person}")) else {
            used.merge(p.id, r.amount, Double::plus)
            r.copy(kind = Kind.REFUND, parentId = p.id, place = r.place.ifBlank { p.title }, category = p.category)
        }
        if (n != r) changed += n
    }
    return changed
}

/** What things cost *you*, once friends' split parts and refunds are taken off. One definition for every screen. */
class Shares(all: List<Txn>) {
    private val ids = all.mapTo(HashSet()) { it.id }
    val lent = lentByParent(all)
    val refunded = refundByParent(all)
    /** A refund we couldn't tie to a purchase still comes off spending, in the month it arrived. */
    private fun looseRefund(t: Txn) = t.kind == Kind.REFUND && (t.parentId == null || t.parentId !in ids) && t.category != "Rent"
    fun counts(t: Txn) = t.countsAsSpending() || looseRefund(t)
    /** A payment minus friends' parts and refunds; anything else as-is. */
    fun net(t: Txn) = if (t.kind == Kind.SPENT) (t.amount - (lent[t.id] ?: 0.0) - (refunded[t.id] ?: 0.0)).coerceAtLeast(0.0) else t.amount
    /** What a transaction adds to "my spending": your share of a payment, minus a loose refund, else nothing. */
    fun spending(t: Txn) = when {
        t.countsAsSpending() -> net(t)
        looseRefund(t) -> -t.amount
        else -> 0.0
    }
}

class Stats(all: List<Txn>, today: LocalDate = LocalDate.now()) {
    val month: YearMonth = YearMonth.from(today)
    private val shares = Shares(all)
    private fun counts(t: Txn) = shares.counts(t)
    private fun share(t: Txn) = shares.spending(t)
    private val thisMonth = all.filter { YearMonth.from(it.date) == month }
    private val spends = thisMonth.filter(::counts)
    val spentToday = spends.filter { it.date == today }.sumOf(::share).coerceAtLeast(0.0)
    val spentMonth = spends.sumOf(::share).coerceAtLeast(0.0)
    val byCategory: List<Pair<String, Double>> =
        spends.groupBy { it.category }.map { (c, l) -> c to l.sumOf(::share) }.filter { it.second > 0 }.sortedByDescending { it.second }
    val investedMonth = thisMonth.filter { it.kind == Kind.INVEST }.sumOf { it.amount }
    val investedTotal = all.filter { it.kind == Kind.INVEST }.sumOf { it.amount }
    val receivedMonth = thisMonth.filter { it.kind == Kind.RECEIVED }.sumOf { it.amount }
    val owed = openLoans(all).sumOf { it.left }
    val daysLeft = month.lengthOfMonth() - today.dayOfMonth + 1
    val projected = spentMonth / today.dayOfMonth * month.lengthOfMonth()

    /** Same days of last month, so "vs last month" is fair mid-month. */
    val lastMonthToDate = all.filter {
        counts(it) && YearMonth.from(it.date) == month.minusMonths(1) && it.date.dayOfMonth <= today.dayOfMonth
    }.sumOf(::share).coerceAtLeast(0.0)

    /** Spending per day for the last 14 days, oldest first. */
    val last14: List<Pair<LocalDate, Double>> = all.filter { counts(it) && it.date > today.minusDays(14) && it.date <= today }
        .groupBy { it.date }.mapValues { (_, l) -> l.sumOf(::share).coerceAtLeast(0.0) }
        .let { m -> (13 downTo 0).map { today.minusDays(it.toLong()).let { d -> d to (m[d] ?: 0.0) } } }

    val biggest = spends.filter { it.kind == Kind.SPENT }.maxByOrNull(::share)?.let { it.copy(amount = share(it)) }?.takeIf { it.amount > 0 }
    val favourite = spends.filter { it.kind == Kind.SPENT && it.title.isNotBlank() }.groupBy { it.title.lowercase() }
        .maxByOrNull { it.value.size }?.value?.takeIf { it.size >= 2 }?.let { it.first().title to it.size }
}

fun csv(all: List<Txn>): String {
    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    return "date,time,type,direction,amount,category,person,place,note\n" + all.joinToString("\n") { t ->
        listOf(t.date.toString(), timeOf(t), t.kind.label, if (t.isOut) "paid" else "received", t.amount.toString(), t.category, t.person, t.place, t.note)
            .joinToString(",") { q(it) }
    }
}

private fun timeOf(t: Txn) = Instant.ofEpochMilli(t.time).atZone(ZoneId.systemDefault()).toLocalTime().withNano(0).toString()

val TYPES = listOf("All", "Paid", "Received") + Kind.entries.map { it.label }

data class Filter(
    val query: String = "",
    val type: String = "All",
    val month: YearMonth? = YearMonth.now(), // null = all time
    val day: LocalDate? = null,
    val person: String = "",
    val place: String = "",
    val category: String = "",
    val min: Double? = null,
    val max: Double? = null,
) {
    val extraCount get() = listOf(day != null, person.isNotBlank(), place.isNotBlank(), category.isNotBlank(), min != null, max != null).count { it }

    fun matches(t: Txn): Boolean {
        if (day != null) { if (t.date != day) return false }
        else if (month != null && YearMonth.from(t.date) != month) return false
        when (type) {
            "All" -> {}
            "Paid" -> if (!t.isOut) return false
            "Received" -> if (t.isOut) return false
            else -> if (t.kind.label != type) return false
        }
        if (person.isNotBlank() && !t.person.contains(person.trim(), true)) return false
        if (place.isNotBlank() && !t.place.contains(place.trim(), true)) return false
        if (category.isNotBlank() && (t.kind != Kind.SPENT || !t.category.equals(category, true))) return false
        if (min != null && t.amount < min) return false
        if (max != null && t.amount > max) return false
        if (query.isNotBlank() && listOf(t.person, t.place, t.note, t.category, t.kind.label, t.sms.orEmpty())
                .none { it.contains(query.trim(), true) }) return false
        return true
    }
}
