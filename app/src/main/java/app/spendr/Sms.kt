package app.spendr

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

data class Hint(val time: Long, val amount: Double, val label: String, val kind: Kind, val category: String, val key: String)

object Sms {
    private const val DAY = 86_400_000L

    /** Bump when parsing rules change: the app then rescans the whole inbox once and re-sorts untouched rows. */
    const val RULES = 3

    /** Bank / UPI SMS come from alphanumeric sender IDs like "VM-HDFCBK"; skip people and "-P" promo senders. */
    fun isBank(address: String) = address.any { it.isLetter() } && !address.endsWith("-P", ignoreCase = true)

    fun toTxn(address: String, body: String, time: Long, hints: List<Hint> = emptyList(), rules: Map<String, Pair<Kind, String>> = emptyMap()): Txn? =
        if (isBank(address)) classify(body, time, hints, rules) else null

    fun toHint(address: String, body: String, time: Long): Hint? {
        if (!isBank(address)) return null
        val h = SmsParser.hint(body) ?: return null
        // "AX-IRCTCi-S" → "IRCTCi" when the notice doesn't name the merchant itself
        val label = h.label.ifBlank { address.split('-').getOrNull(1) ?: address }
        val (kind, category) = when {
            h.fund -> Kind.INVEST to "Other"
            h.refund -> Kind.REFUND to SmsParser.categoryOf("$label $body")
            else -> SmsParser.guess(h.label, true, h.amount)
        }
        return Hint(time, h.amount, label, kind, category, key(body))
    }

    /**
     * Pure function of the SMS (+ hints, + your per-payee choices in [rules]), so stored rows can be re-derived
     * whenever the parsing rules improve.
     */
    fun classify(body: String, time: Long, hints: List<Hint> = emptyList(), rules: Map<String, Pair<Kind, String>> = emptyMap()): Txn? {
        val p = SmsParser.parse(body) ?: return null
        var (kind, category) = SmsParser.guess("$body ${p.party}", p.isOut, p.amount)
        val isPlace = p.viaCard || kind == Kind.INVEST || category != "Other"
        var person = if (isPlace) "" else p.party
        var place = if (isPlace) p.party else ""
        // A bare "A/C debited by INR X" says nothing; a fund confirmation / mandate notice for the same amount nearby does.
        if (p.isOut && p.party.isBlank() && kind == Kind.SPENT && category == "Other") {
            hints.firstOrNull { it.kind != Kind.REFUND && abs(it.amount - p.amount) <= max(1.0, p.amount * 0.001) && abs(it.time - time) <= 3 * DAY }?.let {
                kind = it.kind; category = it.category; place = it.label; person = ""
            }
        }
        // a bare credit right after a merchant said "refund of Rs X initiated" is that refund
        if (!p.isOut && p.party.isBlank() && kind == Kind.RECEIVED) {
            hints.firstOrNull {
                it.kind == Kind.REFUND && abs(it.amount - p.amount) <= max(1.0, p.amount * 0.01) && time >= it.time - DAY && time - it.time <= 10 * DAY
            }?.let { kind = Kind.REFUND; category = it.category; place = it.label; person = "" }
        }
        val t = Txn(
            amount = p.amount, isOut = p.isOut, kind = kind, category = category,
            person = person, place = place, time = time, sms = body, smsKey = key(body),
        )
        // what you picked for this payee ("everything to RAMESH is Food") beats any guess
        return rules[t.payeeKey()]?.let { (k, c) -> t.copy(kind = k, category = c) } ?: t
    }

    // Keyed on the body only: the receiver and the inbox report slightly different timestamps for the same SMS.
    fun key(body: String) = MessageDigest.getInstance("SHA-256").digest(body.trim().toByteArray())
        .joinToString("") { "%02x".format(it) }

    fun importInbox(ctx: Context, since: Long = 0): Int {
        Limits(ctx).applyRentRule()
        val found = mutableListOf<Txn>()
        val hints = mutableListOf<Hint>()
        ctx.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?", arrayOf(since.toString()), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val address = c.getString(0)
                val body = c.getString(1)
                if (address != null && body != null) {
                    toHint(address, body, c.getLong(2))?.let(hints::add) ?: toTxn(address, body, c.getLong(2))?.let(found::add)
                }
            }
        }
        val db = Db.get(ctx)
        db.insertHints(hints)
        val n = db.insertSms(found)
        db.reclassify()
        return n
    }
}

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val address = parts?.firstOrNull()?.originatingAddress ?: return
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val time = System.currentTimeMillis()
        val pending = goAsync()
        Thread {
            try {
                Limits(ctx).applyRentRule()
                val db = Db.get(ctx)
                val hint = Sms.toHint(address, body, time)
                if (hint != null) {
                    db.insertHints(listOf(hint))
                    db.reclassify() // may turn an earlier bare debit into an investment
                } else {
                    val txn = Sms.toTxn(address, body, time, db.hints(), db.rules()) ?: return@Thread
                    if (db.insertSms(listOf(txn)) == 0) return@Thread
                    db.reclassify() // links a refund to its purchase
                    db.bySmsKey(txn.smsKey!!)?.let { Alerts.tracked(ctx, it) }
                }
                Alerts.check(ctx)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
