package app.spendr

/** Turns Indian bank / UPI / card SMS into transactions. Pure Kotlin so it's unit-tested. */
object SmsParser {
    data class Parsed(val amount: Double, val isOut: Boolean, val party: String, val viaCard: Boolean)

    /** Not a transaction itself, but names the fund/payee behind a bank debit that has none. */
    data class HintInfo(val amount: Double, val label: String, val fund: Boolean, val refund: Boolean = false)

    private const val AMT = """([0-9][0-9,]*(?:\.[0-9]{1,2})?)"""
    private val keyedAmount = Regex("""(?i)\b(?:debited|credited|spent|paid|sent|received|withdrawn)\s+(?:by|for|with|of)?\s*(?:rs\.?|inr|₹)?\s*$AMT""")
    private val currencyAmount = Regex("""(?i)(?:\b(?:rs\.?|inr)|₹)\s*$AMT""")
    private val debit = Regex("""(?i)\b(debited|debit|spent|paid|sent|withdrawn|withdrawal|purchase|purchased|transferred|used for)\b""")
    private val credit = Regex("""(?i)\b(credited|received|deposited)\b""")
    private val refund = Regex("""(?i)\b(refund|refunded|reversed|reversal)\b""")
    // e.g. Paytm Money "Rs. 79.11 successfully transferred ... to your bank account as ... settlement"
    private val toYourAccount = Regex("""(?i)\btransferred\b.{0,80}\bto your (?:bank )?(?:a/?c|account)\b""")
    private val skip = Regex(
        """(?i)\b(otp|one[- ]time password|verification code|collect request|has requested|requested money|is due|due on|due date|amount due|due by|total due|""" +
            """will be debited|will be credited|failed|declined|unsuccessful|pre-?approved|eligible for|ready to be credited|loan approved|credited instantly|""" +
            """kyc|cancelled|to beneficiary .{0,60}credited successfully|credited to your (?:\w+ )?(?:number|mobile))\b"""
    )
    private val bankWords = Regex("""(?i)\b(debited|credited)\b""")
    private val fundConfirm = Regex(
        """(?i)(folio|units? (?:are )?allotted|\bnav\b|sip instalment|systematic investment|sys\.? investment|lump\s*su\w*\s+purchase|""" +
            """purchase request|your purchase of|transaction of .{0,30} has been processed|towards your sip)"""
    )
    private val fundName = Regex("""(?i)\b(?:in|under|towards|for)\s+((?:(?!(?:in|under|towards|for|your|of|the)\b)[A-Za-z][\w&'-]*\s+){1,6}?(?:fund|fof))\b""")
    // a merchant saying money is coming back ("Refund of Rs 373 initiated for Swiggy order", IRCTC "Amt 60.36 will be refunded")
    private val refundNotice = Regex("""(?i)(\brefund of\b|\brefund reference\b|\b(?:will|to) (?:be|get) refunded\b|\brefund (?:has been|is) (?:initiated|processed)\b)""")
    private val taxRefund = Regex("""(?i)(\bit refund\b|income tax|tax refund|itdtax)""")
    private val amtWord = Regex("""(?i)\bamt\.?\s*$AMT""")
    private val merchants = Regex(
        """(?i)\b(swiggy|zomato|amazon|flipkart|myntra|ajio|meesho|nykaa|irctc|confirmtkt|airtel|jio|uber|ola|rapido|bookmyshow|makemytrip|redbus|blinkit|zepto|bigbasket|paytm|district)\b"""
    )
    private val mandate = Regex("""(?i)will be debited with (?:rs\.?|inr|₹)\s*$AMT towards (.+?) for (?:the )?""")

    private val outParty = listOf(
        Regex("""(?i)\bto\s+VPA\s+([\w.\-]+@[\w.\-]+)"""),
        Regex("""(?i)UPI/P2[AM]/\d+/([^/\n]+?)(?:/|\s+Not\b|\s+UPI\b|$)"""),
        Regex("""(?i)\b(?:trf|transfer(?:red)?)\s+to\s+([A-Za-z][A-Za-z0-9 .&'-]*?)(?=\s+(?:Ref|UPI|on)\b|\.|$)"""),
        Regex("""(?i);\s*([A-Za-z][A-Za-z0-9 .&'*-]*?)\s+credited"""),
        Regex("""(?i)\bInfo:\s*(?:\w+\s*/\s*)+([A-Za-z][A-Za-z0-9 .&'-]*?)(?=\.\s|\.$|\s+New\b|$)"""),
        Regex("""(?i)\bat\s+([A-Za-z0-9][A-Za-z0-9 .&'*_-]*?)(?=\s+on\b|\s+via\b|\s+Avl\b|\s+Ref\b|\.\s|,|$)"""),
        Regex("""(?i)\bon\s+\d{1,2}-\w{3}-\d{2,4}\s+(?:on|at)\s+([A-Za-z0-9][A-Za-z0-9 .&'*_-]*?)(?=\.\s|\.$|,|\s+Avl\b|$)"""),
        Regex("""(?i)\bto\s+([\w.\-]+@[\w.\-]+)"""),
        Regex("""(?i)\bto\s+([A-Za-z][A-Za-z0-9 .&'-]*?)(?=\s+on\b|\s+Ref\b|\s+UPI\b|\.\s|\.$|$)"""),
    )
    private val inParty = listOf(
        Regex("""(?i)\bVPA\s+([\w.\-]+@[\w.\-]+)"""),
        Regex("""(?i)UPI/P2[AM]/\d+/([^/\n]+?)(?:/|\s+Not\b|\s+UPI\b|$)"""),
        Regex("""(?i)\bInfo:\s*(?:\w+\s*/\s*)+([A-Za-z][A-Za-z0-9 .&'-]*?)(?=\.\s|\.$|\s+New\b|$)"""),
        Regex("""(?i)\b(?:from|by)\s+([\w.\-]+@[\w.\-]+)"""),
        Regex("""(?i)\b(?:from|by)\s+([A-Za-z][A-Za-z0-9 .&'-]*?)(?=\s+on\b|\s+Ref\b|\s+UPI\b|\s*\(|\.\s|\.$|$)"""),
    )
    private val junkParty = Regex("""(?i)^(?:(?:your|you|a/?c|account|ac|the|bank|upi|neft|imps|rtgs|rs|inr|mobile)\b.*|\d.*)""")

    private fun norm(body: String) = body.replace(Regex("""\s+"""), " ").trim()
    private fun num(s: String) = s.replace(",", "").toDoubleOrNull()

    fun hint(body: String): HintInfo? {
        val text = norm(body)
        mandate.find(text)?.let { m -> return num(m.groupValues[1])?.let { HintInfo(it, clean(m.groupValues[2]), false) } }
        if (refundNotice.containsMatchIn(text) && !Regex("""(?i)\bcredited\b""").containsMatchIn(text) && !taxRefund.containsMatchIn(text)) {
            val amount = (currencyAmount.find(text) ?: amtWord.find(text))?.groupValues?.get(1)?.let(::num) ?: return null
            val label = merchants.find(text)?.value?.replaceFirstChar { it.uppercase() }.orEmpty()
            return HintInfo(amount, label, fund = false, refund = true)
        }
        if (!fundConfirm.containsMatchIn(text) || bankWords.containsMatchIn(text)) return null
        val amount = currencyAmount.find(text)?.groupValues?.get(1)?.let(::num) ?: return null
        val label = fundName.find(text)?.groupValues?.get(1)?.trim() ?: "Mutual fund"
        return HintInfo(amount, label, true)
    }

    fun parse(body: String): Parsed? {
        val text = norm(body)
        if (skip.containsMatchIn(text) || hint(text) != null) return null
        val d = debit.find(text)?.range?.first
        val c = credit.find(text)?.range?.first
        if (d == null && c == null) return null
        val isOut = when {
            refund.containsMatchIn(text) || toYourAccount.containsMatchIn(text) -> false
            d == null -> false
            c == null -> true
            else -> d < c // "debited ... and credited to a/c X" is a debit
        }
        val amount = (keyedAmount.find(text) ?: currencyAmount.find(text))?.groupValues?.get(1)?.let(::num) ?: return null
        if (amount <= 0) return null
        val party = (if (isOut) outParty else inParty).firstNotNullOfOrNull { r ->
            r.findAll(text).firstNotNullOfOrNull { m -> clean(m.groupValues[1]).takeIf { it.length >= 2 && !junkParty.matches(it) } }
        }.orEmpty()
        return Parsed(amount, isOut, party, viaCard = Regex("""(?i)\bcard\b""").containsMatchIn(text))
    }

    private fun clean(s: String) = s
        .replace(Regex("""(?i)^(?:neft|imps|rtgs|upi)\s+(?:from\s+)?"""), "")
        .replace(Regex("""(?i)\s+(?:ref|utr|txn)\w*\b.*$"""), "")
        .trim().trimEnd('.', ',', '-', ' ').take(40)

    private fun words(vararg w: String) = Regex("""(?i)\b(""" + w.joinToString("|") { Regex.escape(it) } + """)\b""")

    private val invest = words(
        "zerodha", "groww", "upstox", "angel one", "angelone", "kuvera", "smallcase", "paytm money", "etmoney",
        "et money", "mutual fund", "mutual funds", "mf", "sip", "nps", "ppf", "iccl", "indian clearing", "clearing corporation",
        "nse", "bse", "nsdl", "cdsl", "cams", "kfintech", "indmoney", "scripbox", "fixed deposit", "recurring deposit",
        "auragold", "digital gold", "safegold", "securities", "wealth", "finvasia", "shoonya",
    )
    private val cardBill = Regex(
        """(?i)(credit card (bill|payment)|cc bill|\bcred\b|cred\.club|towards your (credit )?card|payment.{0,40}received.{0,40}card|card.{0,40}payment.{0,40}received)"""
    )
    /** Your rent rule from Limits: payments to this payee above [rentMin] are Rent. Blank = off. */
    @Volatile var rentPayee: String = ""
    @Volatile var rentMin: Double = 30_000.0
    private val selfTransfer = Regex("""(?i)(your other bank a/?c|to own a/?c|own account|self transfer|\bto self\b)""")
    private val categories = linkedMapOf(
        "Food" to words(
            "swiggy", "zomato", "dominos", "domino's", "mcdonald", "mcdonalds", "kfc", "starbucks", "restaurant", "cafe", "eatclub",
            "faasos", "box8", "pizza", "burger", "haldiram", "chaayos", "bakery", "bake", "bakes", "cakes", "mess", "hotel", "food", "foods",
            "caterers", "spice", "bites", "canteen", "biryani", "tea", "coffee", "juice", "juiceberry", "kitchen", "dhaba", "sweets", "amul", "parlour",
        ),
        "Groceries" to words(
            "blinkit", "zepto", "bigbasket", "instamart", "dmart", "jiomart", "grofers", "reliance fresh", "more retail", "kirana", "dunzo",
            "supermarket", "super market", "nature's basket", "mart", "store", "stores", "provision", "provisions", "vegetables", "fruits", "milk", "dairy",
        ),
        "Transport" to words("uber", "ola", "rapido", "metro", "cmrl", "namma yatri", "bmtc", "fastag", "parking", "toll", "transport", "transpo", "rental", "rentals"),
        "Fuel" to words("petrol", "fuel", "hpcl", "bpcl", "iocl", "indian oil", "shell", "filling station", "petroleum", "energy"),
        "Shopping" to words(
            "amazon", "flipkart", "ekart", "myntra", "ajio", "meesho", "nykaa", "tata cliq", "croma", "reliance digital", "decathlon", "ikea",
            "lenskart", "zara", "h&m", "clothing", "fashion", "textiles", "silks", "footwear", "computers", "electronics", "mobiles", "shop",
        ),
        "Bills" to words(
            "airtel", "jio", "vodafone", "bsnl", "electricity", "bescom", "tneb", "msedcl", "tata power", "adani", "gas", "broadband",
            "act fibernet", "recharge", "dth", "tata play", "postpaid", "bbps", "bill payments", "water", "insurance", "lic",
            "anthropic", "claude", "openai", "chatgpt",
        ),
        "Entertainment" to words(
            "netflix", "spotify", "hotstar", "jiohotstar", "prime video", "bookmyshow", "pvr", "inox", "youtube", "steam", "playstation",
            "sonyliv", "zee5", "cinema", "cinemas", "movies", "theatre", "entertainment", "scribd",
        ),
        "Health" to words(
            "pharmacy", "pharmac", "pharma", "apollo", "medplus", "medplusmart", "pharmeasy", "1mg", "netmeds", "hospital", "hospitals", "hosp",
            "clinic", "diagnostic", "practo", "cult.fit", "cultfit", "medical", "medicals", "chemist", "dental", "eye", "cmc", "gym", "fitness", "fit",
        ),
        "Travel" to words(
            "irctc", "indian railways", "railways", "uts", "confirm ticket", "makemytrip", "goibibo", "cleartrip", "indigo", "air india", "akasa",
            "redbus", "abhibus", "oyo", "airbnb", "ixigo", "yatra", "booking.com", "booking com",
        ),
        "Rent" to words("rent", "nobroker", "housing.com"),
        "Cash" to words("atm", "cash withdrawal"),
        "Education" to words("udemy", "coursera", "byju", "unacademy", "school", "college", "university", "fees", "institute", "academy", "tuition"),
    )

    fun categoryOf(text: String) = categories.entries.firstOrNull { it.value.containsMatchIn(text) }?.key ?: "Other"

    /** Best guess for (kind, category). [text] should include the SMS body. */
    fun guess(text: String, isOut: Boolean, amount: Double = 0.0): Pair<Kind, String> = when {
        cardBill.containsMatchIn(text) -> Kind.EXCLUDED to "Other" // card spends are counted already; the bill payment would double them
        selfTransfer.containsMatchIn(text) -> Kind.EXCLUDED to "Other"
        !isOut && refund.containsMatchIn(text) && !taxRefund.containsMatchIn(text) -> Kind.REFUND to categoryOf(text)
        !isOut -> Kind.RECEIVED to "Other"
        rentPayee.isNotBlank() && amount > rentMin && Regex("""(?i)\b${Regex.escape(rentPayee.trim())}\b""").containsMatchIn(text) -> Kind.SPENT to "Rent"
        invest.containsMatchIn(text) -> Kind.INVEST to "Other"
        else -> Kind.SPENT to categoryOf(text)
    }
}
