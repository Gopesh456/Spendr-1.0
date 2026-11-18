package app.spendr

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "spendr.db", null, 4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE txn(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount REAL NOT NULL, isOut INTEGER NOT NULL, kind TEXT NOT NULL,
                category TEXT NOT NULL, person TEXT NOT NULL, place TEXT NOT NULL, note TEXT NOT NULL,
                time INTEGER NOT NULL, sms TEXT, smsKey TEXT UNIQUE, loanId INTEGER)"""
        )
        db.execSQL("CREATE INDEX txn_time ON txn(time)")
        // SMS transactions the user deleted, so a rescan doesn't bring them back
        db.execSQL("CREATE TABLE gone(smsKey TEXT PRIMARY KEY)")
        onUpgrade(db, 1, 4)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // edited = touched by the user, so rule updates never overwrite it
            db.execSQL("ALTER TABLE txn ADD COLUMN edited INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE txn SET edited=1 WHERE sms IS NULL OR note!='' OR loanId IS NOT NULL OR kind IN ('LENT','REPAY')")
            db.execSQL("CREATE TABLE hint(key TEXT PRIMARY KEY, time INTEGER NOT NULL, amount REAL NOT NULL, label TEXT NOT NULL, kind TEXT NOT NULL, category TEXT NOT NULL)")
        }
        if (oldVersion < 3) db.execSQL("ALTER TABLE txn ADD COLUMN parentId INTEGER")
        // your per-payee choice of type + category ("out:ramesh kumar" → Spending/Food)
        if (oldVersion < 4) db.execSQL("CREATE TABLE rule(payee TEXT PRIMARY KEY, kind TEXT NOT NULL, category TEXT NOT NULL)")
    }

    // shortcut: whole table in memory; fine for tens of thousands of rows, page it if it ever gets slow
    fun all(): List<Txn> = readableDatabase.rawQuery("SELECT * FROM txn ORDER BY time DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(c.toTxn()) }
    }

    fun bySmsKey(key: String): Txn? =
        readableDatabase.rawQuery("SELECT * FROM txn WHERE smsKey=?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.toTxn() else null }

    fun save(t: Txn): Long {
        val cv = t.values().apply { put("edited", 1) }
        return if (t.id == 0L) writableDatabase.insertOrThrow("txn", null, cv)
        else t.id.also { writableDatabase.update("txn", cv, "id=?", arrayOf(it.toString())) }
    }

    /**
     * Makes [parent]'s friend loans match [shares] (name → amount): updates friends already on it, adds new ones,
     * removes ones no longer in the split. Each loan shows in Lent and comes off the payment's spending.
     */
    fun setSplit(parent: Txn, shares: Map<String, Double>) {
        val db = writableDatabase
        val kids = db.rawQuery("SELECT * FROM txn WHERE kind='LENT' AND parentId=?", arrayOf(parent.id.toString())).use { c ->
            buildList { while (c.moveToNext()) add(c.toTxn()) }
        }
        db.beginTransaction()
        try {
            for ((name, amount) in shares) {
                val kid = kids.firstOrNull { it.person.equals(name, true) }
                save(kid?.copy(amount = amount) ?: Txn(amount = amount, kind = Kind.LENT, person = name, place = parent.title, time = parent.time, parentId = parent.id, note = "Split"))
            }
            for (k in kids) if (shares.keys.none { it.equals(k.person, true) }) db.delete("txn", "id=?", arrayOf(k.id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Puts a deleted txn back with its old id (undo). Repayments still point at it, so loans come back intact. */
    fun restore(t: Txn) {
        writableDatabase.insertWithOnConflict("txn", null, t.values().apply { put("id", t.id) }, SQLiteDatabase.CONFLICT_REPLACE)
        t.smsKey?.let { writableDatabase.delete("gone", "smsKey=?", arrayOf(it)) }
    }

    fun delete(t: Txn) {
        writableDatabase.delete("txn", "id=?", arrayOf(t.id.toString()))
        t.smsKey?.let { writableDatabase.insertWithOnConflict("gone", null, ContentValues().apply { put("smsKey", it) }, SQLiteDatabase.CONFLICT_IGNORE) }
    }

    /** Inserts new SMS txns, skipping ones already stored or deleted. Returns how many were added. */
    fun insertSms(list: List<Txn>): Int {
        val db = writableDatabase
        val gone = db.rawQuery("SELECT smsKey FROM gone", null).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        var n = 0
        db.beginTransaction()
        try {
            for (t in list) if (t.smsKey !in gone && db.insertWithOnConflict("txn", null, t.values(), SQLiteDatabase.CONFLICT_IGNORE) != -1L) n++
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    fun insertHints(list: List<Hint>) {
        for (h in list) writableDatabase.insertWithOnConflict("hint", null, ContentValues().apply {
            put("key", h.key); put("time", h.time); put("amount", h.amount); put("label", h.label); put("kind", h.kind.name); put("category", h.category)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun hints(): List<Hint> = readableDatabase.rawQuery("SELECT key, time, amount, label, kind, category FROM hint", null).use { c ->
        buildList {
            while (c.moveToNext()) {
                val kind = runCatching { Kind.valueOf(c.getString(4)) }.getOrDefault(Kind.INVEST)
                add(Hint(c.getLong(1), c.getDouble(2), c.getString(3), kind, c.getString(5), c.getString(0)))
            }
        }
    }

    fun rules(): Map<String, Pair<Kind, String>> = readableDatabase.rawQuery("SELECT payee, kind, category FROM rule", null).use { c ->
        buildMap { while (c.moveToNext()) runCatching { Kind.valueOf(c.getString(1)) }.getOrNull()?.let { put(c.getString(0), it to c.getString(2)) } }
    }

    /**
     * Gives every transaction with [t]'s payee (same direction) its type + category, and remembers that for new SMS
     * from them. Only for [RULE_KINDS]; returns how many other transactions changed.
     */
    fun applyToPayee(t: Txn): Int {
        val key = t.payeeKey() ?: return 0
        if (t.kind !in RULE_KINDS) return 0
        val db = writableDatabase
        val others = all().filter { it.id != t.id && it.kind in RULE_KINDS && it.payeeKey() == key && (it.kind != t.kind || it.category != t.category) }
        db.beginTransaction()
        try {
            db.insertWithOnConflict("rule", null, ContentValues().apply {
                put("payee", key); put("kind", t.kind.name); put("category", t.category)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            for (o in others) db.update("txn", ContentValues().apply { put("kind", t.kind.name); put("category", t.category) }, "id=?", arrayOf(o.id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return others.size
    }

    /**
     * Re-derives every SMS transaction the user hasn't touched with the current rules: fixes old guesses,
     * drops messages that turned out not to be transactions, links bare debits to fund hints and refunds to purchases.
     */
    fun reclassify() {
        val db = writableDatabase
        val hints = hints()
        val rules = rules()
        val all = all()
        fun auto(t: Txn) = t.sms != null && !t.edited
        val current = all.mapNotNull { t ->
            if (!auto(t)) return@mapNotNull t
            val n = Sms.classify(t.sms!!, t.time, hints, rules) ?: return@mapNotNull null
            when {
                // a plain "Other" guess never overrides a more specific one (maybe set by hand before edits were tracked)
                n.kind == Kind.SPENT && n.category == "Other" && !(t.kind == Kind.SPENT && t.category == "Other") -> t
                else -> t.copy(
                    amount = n.amount, isOut = n.isOut, kind = n.kind, category = n.category, person = n.person, place = n.place,
                    parentId = if (n.isOut) t.parentId else null, // credits get re-linked below
                )
            }
        }
        val linked = linkRefunds(current, ::auto).associateBy { it.id }
        val final = current.map { linked[it.id] ?: it }.associateBy { it.id }
        db.beginTransaction()
        try {
            for (t in all) {
                if (!auto(t)) continue
                val id = arrayOf(t.id.toString())
                val f = final[t.id]
                if (f == null) db.delete("txn", "id=?", id)
                else if (f != t) db.update("txn", ContentValues().apply {
                    put("amount", f.amount); put("isOut", if (f.isOut) 1 else 0); put("kind", f.kind.name)
                    put("category", f.category); put("person", f.person); put("place", f.place); put("parentId", f.parentId)
                }, "id=?", id)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Txn.values() = ContentValues().apply {
        put("amount", amount); put("isOut", if (isOut) 1 else 0); put("kind", kind.name)
        put("category", category); put("person", person.trim()); put("place", place.trim()); put("note", note.trim())
        put("time", time); put("sms", sms); put("smsKey", smsKey); put("loanId", loanId); put("parentId", parentId)
        put("edited", if (edited) 1 else 0)
    }

    private fun Cursor.toTxn(): Txn {
        fun s(col: String) = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
        return Txn(
            id = getLong(getColumnIndexOrThrow("id")),
            amount = getDouble(getColumnIndexOrThrow("amount")),
            isOut = getInt(getColumnIndexOrThrow("isOut")) == 1,
            kind = runCatching { Kind.valueOf(s("kind")!!) }.getOrDefault(Kind.SPENT),
            category = s("category").orEmpty(), person = s("person").orEmpty(),
            place = s("place").orEmpty(), note = s("note").orEmpty(),
            time = getLong(getColumnIndexOrThrow("time")),
            sms = s("sms"), smsKey = s("smsKey"), loanId = s("loanId")?.toLongOrNull(), parentId = s("parentId")?.toLongOrNull(),
            edited = s("edited") == "1",
        )
    }

    companion object {
        @Volatile private var instance: Db? = null
        fun get(ctx: Context) = instance ?: synchronized(this) { instance ?: Db(ctx.applicationContext).also { instance = it } }
    }
}
