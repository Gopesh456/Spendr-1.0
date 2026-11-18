package app.spendr

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import java.time.LocalDate
import java.time.YearMonth

/** Pre-filled rent payee for the rent rule (blank = off); set it under Limits. */
const val DEFAULT_RENT_PAYEE = ""

/** Spending limits in rupees; 0 means "no limit". */
class Limits(ctx: Context) {
    private val p = ctx.getSharedPreferences("limits", Context.MODE_PRIVATE)
    private fun get(k: String) = p.getString(k, null)?.toDoubleOrNull() ?: 0.0
    private fun set(k: String, v: Double) = p.edit { putString(k, v.toString()) }

    var daily: Double get() = get("daily"); set(v) = set("daily", v)
    var monthly: Double get() = get("monthly"); set(v) = set("monthly", v)
    fun category(c: String) = get("cat_$c")
    fun setCategory(c: String, v: Double) = set("cat_$c", v)

    /** Payments to this payee above [rentMin] are Rent (and so not counted as spending). */
    var rentPayee: String get() = p.getString("rentPayee", DEFAULT_RENT_PAYEE)!!; set(v) = p.edit { putString("rentPayee", v.trim()) }
    var rentMin: Double get() = get("rentMin").takeIf { it > 0 } ?: 30_000.0; set(v) = set("rentMin", v)
    fun applyRentRule() { SmsParser.rentPayee = rentPayee; SmsParser.rentMin = rentMin }
}

object Alerts {
    private const val CHANNEL = "limits"

    /** Notifies once at 80% and once at 100% of each limit, per day / month. */
    fun check(ctx: Context) {
        val all = Db.get(ctx).all()
        val today = LocalDate.now()
        val s = Stats(all, today)
        val lim = Limits(ctx)
        val month = YearMonth.from(today)
        val checks = buildList {
            add(Triple("Daily limit", "day|$today", s.spentToday to lim.daily))
            add(Triple("Monthly limit", "month|$month", s.spentMonth to lim.monthly))
            for ((c, spent) in s.byCategory) add(Triple("$c limit", "cat|$c|$month", spent to lim.category(c)))
        }
        val sent = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        for ((name, period, pair) in checks) {
            val (spent, limit) = pair
            if (limit <= 0) continue
            val level = when { spent >= limit -> 100; spent >= limit * 0.8 -> 80; else -> continue }
            val key = "$period|$level"
            if (sent.getBoolean(key, false)) continue
            val text = if (level == 100) "You've spent ${money(spent)} — over your ${money(limit)} ${name.lowercase()} 🚨"
            else "Heads up: ${money(spent)} of ${money(limit)} used. Only ${money(limit - spent)} left ⚠️"
            if (notify(ctx, key.hashCode(), if (level == 100) "$name crossed" else "$name almost reached", text)) sent.edit { putBoolean(key, true) }
        }
    }

    /** "₹349 at Swiggy logged as Food" — tap opens it so a wrong guess is one tap to fix. */
    fun tracked(ctx: Context, t: Txn) {
        val what = if (t.kind == Kind.SPENT) t.category else t.kind.label
        notify(
            ctx, t.id.toInt(), "${if (t.isOut) "−" else "+"}${money(t.amount)}${t.title.takeIf { it != what }?.let { " · $it" }.orEmpty()}",
            "Logged as ${t.emoji()} $what. Tap to change.", channel = TRACKED, txnId = t.id,
        )
    }

    private const val TRACKED = "tracked"

    private fun notify(ctx: Context, id: Int, title: String, text: String, channel: String = CHANNEL, txnId: Long = 0): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Spending limits", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(TRACKED, "New transactions", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TXN, txnId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val open = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(
            id, Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_notif).setColor(0xFF5B3DF5.toInt())
                .setContentTitle(title).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).build()
        )
        return true
    }
}
