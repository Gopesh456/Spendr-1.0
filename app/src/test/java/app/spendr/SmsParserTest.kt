package app.spendr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SmsParserTest {
    private fun p(s: String) = SmsParser.parse(s)

    @Test fun hdfcUpiSent() {
        val r = p("Sent Rs.500.00 From HDFC Bank A/C *1234 To SWIGGY On 17/11/26 Ref 123456789012 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808")!!
        assertEquals(500.0, r.amount, 0.0); assertEquals(true, r.isOut); assertEquals("SWIGGY", r.party)
    }

    @Test fun sbiDebitedByNoCurrency() {
        val r = p("Dear UPI user A/C X1234 debited by 250.0 on date 17Nov26 trf to RAHUL KUMAR Refno 431212345678. If not u? call 1800111109. -SBI")!!
        assertEquals(250.0, r.amount, 0.0); assertEquals(true, r.isOut); assertEquals("RAHUL KUMAR", r.party)
    }

    @Test fun sbiDebitedAndCreditedIsDebit() {
        val r = p("Your a/c no. XXXXXXXX1234 is debited for Rs.1,200.00 on 17-11-26 and credited to a/c no. XXXXXXXX5678 (UPI Ref no 431212345678)")!!
        assertEquals(1200.0, r.amount, 0.0); assertEquals(true, r.isOut)
    }

    @Test fun iciciMerchantCredited() {
        val r = p("ICICI Bank Acct XX123 debited for Rs 349.00 on 17-Nov-26; ZOMATO credited. UPI:431212345678. Call 18002662 for dispute. SMS BLOCK 123 to 9215676766.")!!
        assertEquals(349.0, r.amount, 0.0); assertEquals(true, r.isOut); assertEquals("ZOMATO", r.party)
    }

    @Test fun axisP2m() {
        val r = p("INR 899.00 debited A/c no. XX1234 17-11-26 12:00:00 UPI/P2M/431212345678/BLINKIT Not you? SMS BLOCKUPI Cust ID to 919951860002 Axis Bank")!!
        assertEquals(899.0, r.amount, 0.0); assertEquals(true, r.isOut); assertEquals("BLINKIT", r.party)
    }

    @Test fun cardSpentAt() {
        val r = p("Rs.1,299.00 spent on HDFC Bank Card x1234 at AMAZON on 2026-11-17:12:00:00 Avl bal Rs 45,000.00")!!
        assertEquals(1299.0, r.amount, 0.0); assertEquals(true, r.isOut); assertEquals("AMAZON", r.party); assertEquals(true, r.viaCard)
    }

    @Test fun iciciCardOnMerchant() {
        val r = p("INR 2,499.00 spent using ICICI Bank Card XX1234 on 17-Nov-26 on MYNTRA. Avl Limit: INR 50,000.00. If not you, call 1800 2662.")!!
        assertEquals(2499.0, r.amount, 0.0); assertEquals("MYNTRA", r.party)
    }

    @Test fun moneyReceivedFromVpa() {
        val r = p("Money Received - INR 1,000.00 in your HDFC Bank A/c xx1234 on 17-11-26 by A/c linked to VPA friend@okaxis (UPI Ref No 431212345678)")!!
        assertEquals(1000.0, r.amount, 0.0); assertEquals(false, r.isOut); assertEquals("friend@okaxis", r.party)
    }

    @Test fun salaryNeft() {
        val r = p("Your A/c XX1234 credited with INR 75,000.00 on 17-11-26 by NEFT from ACME CORP. Avl Bal INR 1,20,000.00")!!
        assertEquals(75000.0, r.amount, 0.0); assertEquals(false, r.isOut); assertEquals("ACME CORP", r.party)
    }

    @Test fun refundIsIncoming() {
        assertEquals(false, p("Refund of Rs 200.00 from AMAZON has been credited to your HDFC Bank Card x1234")!!.isOut)
    }

    @Test fun ignoresNoise() {
        assertNull(p("123456 is your OTP for txn of Rs 500.00 at AMAZON. Do not share it with anyone."))
        assertNull(p("Rahul has requested money of Rs 300 from you on Google Pay. Pay now."))
        assertNull(p("Your credit card statement is ready. Total amount due Rs 12,000 is due on 05-12-26."))
        assertNull(p("Your mandate for Rs 199 will be debited on 20-11-26 for NETFLIX"))
        assertNull(p("Hey are we meeting at 5? bring Rs 200"))
        assertNull(p("Your A/c XX1234 balance is Rs 5,000.00 as on 17-11-26"))
    }

    @Test fun guesses() {
        assertEquals(Kind.SPENT to "Food", SmsParser.guess("paid to swiggy", true))
        assertEquals(Kind.INVEST to "Other", SmsParser.guess("debited to ZERODHA BROKING", true))
        assertEquals(Kind.EXCLUDED to "Other", SmsParser.guess("Payment of Rs 5000 received towards your credit card", false))
        assertEquals(Kind.SPENT to "Other", SmsParser.guess("debited auto-debit for something", true))
        assertEquals(Kind.RECEIVED to "Other", SmsParser.guess("credited by friend", false))
    }

    @Test fun smsFromPeopleIgnored() {
        assertNull(Sms.toTxn("+919876543210", "Sent Rs.500.00 From HDFC Bank A/C *1234 To SWIGGY On 17/11/26", 0))
        val t = Sms.toTxn("VM-HDFCBK", "Sent Rs.500.00 From HDFC Bank A/C *1234 To SWIGGY On 17/11/26", 0)!!
        assertEquals("SWIGGY", t.place); assertEquals("Food", t.category)
    }

    // ---- formats seen in the user's real inbox (IDFC FIRST, SBI, AMCs) ----

    private val bank = "JD-IDFCFB-S"

    @Test fun idfcMerchantIsFood() {
        val t = Sms.toTxn(bank, "Your A/c XX1234 debited by Rs. 349.00 on 16/11/26; ANNAPOORNA MESS credited. RRN 612345678901. Available balance Rs. 1,000.00. Team IDFC FIRST Bank", 0)!!
        assertEquals("ANNAPOORNA MESS", t.place); assertEquals("Food", t.category); assertEquals(Kind.SPENT, t.kind)
    }

    @Test fun bareDebitBecomesFundViaConfirmation() {
        val day = 86_400_000L
        val hint = Sms.toHint("VM-IVZIND-S", "Folio XXXXXXX9999 - Your SIP instalment of Rs. 2189.89 towards Invesco India Midcap Fund - Direct Plan Growth - Growth is processed. Units Allotted: 9.1", 2 * day)!!
        assertEquals("Invesco India Midcap Fund", hint.label); assertEquals(Kind.INVEST, hint.kind)
        val bare = "Your A/C XXXXX1234 is debited by INR 2,190.00 on 15/10/25 00:38. New Bal :INR 14,650.86. Call us on 180010888 for dispute. Team IDFC FIRST Bank"
        assertEquals(Kind.SPENT, Sms.toTxn(bank, bare, day)!!.kind) // no hint → unknown
        val t = Sms.toTxn(bank, bare, day, listOf(hint))!!
        assertEquals(Kind.INVEST, t.kind); assertEquals("Invesco India Midcap Fund", t.place)
        assertEquals(Kind.SPENT, Sms.toTxn(bank, bare, 9 * day, listOf(hint))!!.kind) // too far apart → not linked
    }

    @Test fun fundConfirmationsAreHintsNotTransactions() {
        val kotak = "Your purchase request on 23rd January , 2026 under folio no. 11111111 for Kotak Multi Asset Omni FOF Dir-Gr for Rs. 105000 have been made successfully. Regards, Kotak Mutual Fund."
        val axis = "Dear Investor, Payment of Rs. 22.00 towards your SIP in Axis Small Cap Fund Direct Growth has been received and 0.161 units at NAV 136.77 are allotted in Folio 123. Axis MF"
        val ipru = "Dear Investor, Your Purchase of Rs.1,04,994.75 in Folio 22222222 in Passive Multi-Asset Fund of Funds DP Growth for 6324.718 units has been processed for NAV of 16.60"
        assertNull(SmsParser.parse(kotak)); assertNull(SmsParser.parse(axis)); assertNull(SmsParser.parse(ipru))
        assertEquals("Kotak Multi Asset Omni FOF", SmsParser.hint(kotak)!!.label)
        assertEquals("Axis Small Cap Fund", SmsParser.hint(axis)!!.label)
        assertEquals(104994.75, SmsParser.hint(ipru)!!.amount, 0.0)
        val mandate = Sms.toHint("JM-IDFCFB-S", "Your account will be debited with Rs 2,190.00 towards Paytm Money Limited for the UPI Mandate on 15/10/2025. Pause mandate to stop execution. IDFC FIRST Bank", 0)!!
        assertEquals("Paytm Money Limited", mandate.label); assertEquals(Kind.INVEST, mandate.kind)
        assertEquals(Kind.SPENT to "Entertainment", Sms.toHint(bank, "Your account will be debited with Rs 199.00 towards NETFLIX COM for the Upi Mandate on 25/09/2026", 0)!!.let { it.kind to it.category })
        // a real bank debit that merely names a fund house is still a transaction
        assertEquals(Kind.INVEST, Sms.toTxn(bank, "Your A/c XX1234 debited by Rs. 50,000.00 on 03/06/26; MUTUAL FUNDS NCL credited. RRN 1. Team IDFC FIRST Bank", 0)!!.kind)
    }

    @Test fun rtgsToClearingCorpIsInvestmentAndStatusIsSkipped() {
        val t = Sms.toTxn(bank, "Your A/c XXXXXXX1234 has been debited by Rs. 98,000.18 on 29/09/25. Info: RTGS/ IDFBR00000/INDIAN CLEARING CORPORATION LTD. New bal: Rs. 1,04,001.16. Team IDFC FIRST Bank", 0)!!
        assertEquals(Kind.INVEST, t.kind); assertEquals("INDIAN CLEARING CORPORATION LTD", t.place)
        assertNull(SmsParser.parse("Rs 98,000.18/- transferred via RTGS to Beneficiary A/c no. XXXXXXXXXX is credited successfully on 29/09/25. (UTR no: IDFBR1). Team IDFC FIRST Bank"))
    }

    @Test fun rentRuleFilesBigPaymentsToTheRentPayee() {
        SmsParser.rentPayee = "Asha"; SmsParser.rentMin = 30_000.0
        fun sms(amt: String) = "Your A/c XX1234 debited by Rs. $amt on 11/11/26; Mrs. K Asha credited. RRN 1. Available balance Rs. 5.00. Team IDFC FIRST Bank"
        assertEquals(Kind.SPENT to "Rent", Sms.toTxn(bank, sms("38,257.00"), 0)!!.let { it.kind to it.category })
        assertEquals("Other", Sms.toTxn(bank, sms("30,000.00"), 0)!!.category)
        assertEquals("Other", Sms.toTxn(bank, sms("500.00"), 0)!!.category)
        SmsParser.rentPayee = "" // blank name = rule off
        assertEquals("Other", Sms.toTxn(bank, sms("38,257.00"), 0)!!.category)
    }

    @Test fun moreRealFormats() {
        val sbi = SmsParser.parse("Dear SBI User, your A/c X1234-credited by Rs.70 on 29Jul25 transfer from Ravi Kumar Sharma Ref No 123456789 -SBI")!!
        assertEquals(false, sbi.isOut); assertEquals("Ravi Kumar Sharma", sbi.party)
        val settle = SmsParser.parse("Rs. 79.11 successfully transferred on 18 April 2026 to your bank account as SEBI-mandated Quarterly settlement via Paytm Money Ltd. Transaction no: TXN1.")!!
        assertEquals(false, settle.isOut)
        assertNull(SmsParser.parse("Paapapay - Loan Approved! Rs.20,000 credited instantly Get now- http://go3.in/x"))
        assertNull(Sms.toTxn("AD-650022-P", "Get Rs. 500 credited to your wallet", 0))
        assertEquals("Cash", Sms.toTxn(bank, "Debit Rs.10,000.00 from A/C XX1234 at ATM AB123456 on 01/06/26 20:23. Not you? Call 180010888", 0)!!.category)
        assertEquals(Kind.EXCLUDED, Sms.toTxn(bank, "Success! Your FD a/c XXXXX123456 is closed. Amt. of INR 5,003.00 has been debited on 10/07/26 at 21:31 and sent via NEFT to Your other bank a/c. IDFC FIRST Bank", 0)!!.kind)
    }

    @Test fun splitPaymentCountsOnlyYourShare() {
        val today = LocalDate.of(2026, 11, 17)
        val all = listOf(
            Txn(1, 1200.0, true, Kind.SPENT, category = "Food", place = "Swiggy", time = at(today)),
            Txn(2, 500.0, true, Kind.LENT, person = "Rahul", parentId = 1, time = at(today)),
            Txn(3, 300.0, true, Kind.LENT, person = "Amit", parentId = 1, time = at(today)),
            Txn(4, 300.0, false, Kind.REPAY, loanId = 3, time = at(today)), // Amit paid back
            Txn(5, 200.0, true, Kind.SPENT, category = "Transport", time = at(today)),
            Txn(6, 200.0, true, Kind.LENT, person = "Rahul", parentId = 5, time = at(today)), // whole ride was for Rahul
            Txn(7, 38257.0, true, Kind.SPENT, category = "Rent", person = "Mrs. K Asha", time = at(today)), // rent isn't "my spending"
        )
        val s = Stats(all, today)
        assertEquals(400.0, s.spentMonth, 0.0) // 1200 - 800 lent, ride fully lent, rent left out
        assertEquals(400.0, s.spentToday, 0.0)
        assertEquals(listOf("Food" to 400.0), s.byCategory) // a fully-lent category disappears
        assertEquals(700.0, s.owed, 0.0) // Rahul 500 + 200; Amit settled
        assertEquals(listOf(1L, 4L, 5L, 7L), all.forActivity().map { it.id }) // loans live inside their payment
        assertEquals(mapOf(1L to 800.0, 5L to 200.0), lentByParent(all))
        // loans added by hand never show as transactions, even if their payment is gone (they live in the Lent tab)
        assertEquals(listOf(4L, 5L, 7L), all.drop(1).forActivity().map { it.id })
        val cashLoan = Txn(8, 2000.0, true, Kind.LENT, person = "Rahul", time = at(today))
        val smsLoan = Txn(9, 1500.0, true, Kind.LENT, person = "Amit", time = at(today), sms = "debited by Rs. 1,500.00; AMIT credited")
        assertEquals(listOf(9L), listOf(cashLoan, smsLoan).forActivity().map { it.id }) // a bank payment marked Lent is real money out
        val withLoans = all + cashLoan + smsLoan
        assertEquals(700.0 + 2000.0 + 1500.0, Stats(withLoans, today).owed, 0.0) // ...and both are still owed to you
        // and no loan ever counts towards what you spent/paid (Home, limits, Activity's tile and day totals)
        assertEquals(400.0, Stats(withLoans, today).spentMonth, 0.0)
        assertEquals(400.0, withLoans.forActivity().sumOf(Shares(withLoans)::spending), 0.0)
    }

    // ---- refunds ----

    @Test fun refundNoticesAndRefundSms() {
        val swiggy = SmsParser.hint("Refund of Rs 373.0 has been initiated for Swiggy order 1234. Updated balance should reflect in 2 hours. https://r.swiggy.com/refunds")!!
        assertEquals(373.0, swiggy.amount, 0.0); assertEquals("Swiggy", swiggy.label); assertEquals(true, swiggy.refund)
        val irctc = Sms.toHint("AX-IRCTCi-S", "PNR 123 ticket cancelled. Amt 60.36 will be refunded within 3-4 days. Please check your account.-IRCTC", 0)!!
        assertEquals(60.36, irctc.amount, 0.0); assertEquals(Kind.REFUND, irctc.kind); assertEquals("Travel", irctc.category)
        // a bank SMS that says "refund ... credited" is a refund transaction itself
        assertEquals(Kind.REFUND to "Shopping", Sms.toTxn(bank, "Refund of Rs 200.00 from AMAZON has been credited to your A/c XX1234", 0)!!.let { it.kind to it.category })
        // an income-tax refund is income, not money back from a purchase
        assertEquals(Kind.RECEIVED, Sms.toTxn("VK-SBIBNK-S", "Dear Customer, An IT Refund amount of Rs 53570 for AY-2026-27 has been credited to your account XXX1234 on 2026-08-21. -SBI", 0)!!.kind)
        // a bare IDFC credit right after Swiggy's notice is that refund
        val credit = "Your A/C XXXXX1234 is credited with INR 373.00 on 27/08/26 15:55. Your new balance is INR 1,63,730.80. Team IDFC FIRST Bank"
        val hint = Sms.toHint("TX-SWIGGY-S", "Refund of Rs 373.0 has been initiated for Swiggy order 1234.", 1000)!!
        val t = Sms.toTxn(bank, credit, 2000, listOf(hint))!!
        assertEquals(Kind.REFUND, t.kind); assertEquals("Swiggy", t.place); assertEquals("Food", t.category)
        assertEquals(Kind.RECEIVED, Sms.toTxn(bank, credit, 2000)!!.kind) // without the notice it's just money in
    }

    @Test fun refundsLinkToTheirPurchase() {
        val day = 86_400_000L
        val amazon = Txn(1, 17503.0, true, Kind.SPENT, category = "Shopping", place = "Amazon India", time = 10 * day)
        val cinema = Txn(2, 160.0, true, Kind.SPENT, category = "Entertainment", place = "GALAXY CINEMAS", time = 10 * day)
        val swiggy = Txn(3, 373.0, true, Kind.SPENT, category = "Food", place = "SWIGGY", time = 10 * day)
        // a credit that names the shop as its sender ("... credited by AMAZON") vs a bare one (also how friends pay you)
        fun fromShop(id: Long, amt: Double, at: Long, shop: String = "AMAZON") = Txn(id, amt, false, Kind.RECEIVED, person = shop, time = at, sms = "x")
        fun bare(id: Long, amt: Double, at: Long) = Txn(id, amt, false, Kind.RECEIVED, time = at, sms = "x")
        fun link(vararg r: Txn) = linkRefunds(listOf(amazon, cinema, swiggy) + r) { it.sms != null }.associateBy { it.id }

        // bare credits are never guessed to be refunds, however well the amount fits
        assertNull(link(bare(10, 17483.0, 20 * day))[10])
        assertNull(link(bare(10, 17503.0, 20 * day))[10])
        assertNull(link(bare(10, 373.0, 11 * day))[10])

        assertEquals(1L, link(fromShop(10, 17483.0, 20 * day))[10]!!.parentId) // ₹20 fee kept: still Amazon's refund
        assertEquals(Kind.REFUND, link(fromShop(10, 17503.0, 20 * day))[10]!!.kind) // full refund
        assertEquals(1L, link(fromShop(10, 9000.0, 20 * day))[10]!!.parentId) // part of the order returned
        // from the shop but no purchase to tie it to (too late / before it): still a refund, it just comes off this month
        assertEquals(Kind.REFUND to null, link(fromShop(10, 17503.0, 75 * day))[10]!!.let { it.kind to it.parentId })
        assertEquals(Kind.REFUND to null, link(fromShop(10, 17503.0, 5 * day))[10]!!.let { it.kind to it.parentId })
        assertNull(link(fromShop(10, 160.0, 11 * day, shop = "Rahul Kumar"))[10]) // a friend is not a shop
        assertEquals(3L, link(fromShop(10, 361.0, 11 * day, shop = "Swiggy Ltd"))[10]!!.parentId) // ₹12 fee kept

        val swiggyRefund = Txn(11, 373.0, false, Kind.REFUND, place = "Swiggy", category = "Food", time = 11 * day, sms = "x")
        assertEquals(3L, link(swiggyRefund)[11]!!.parentId)
        // closest amount wins when two purchases fit
        val small = Txn(4, 999.0, true, Kind.SPENT, place = "Flipkart", time = 9 * day)
        val big = Txn(5, 1004.0, true, Kind.SPENT, place = "Flipkart", time = 9 * day)
        assertEquals(4L, linkRefunds(listOf(small, big, fromShop(10, 990.0, 12 * day, "Flipkart"))) { it.sms != null }.single().parentId)
        assertNull(SmsParser.parse("Hi, recharge of Rs. 649 successfully credited to your Airtel number 98xxxxxx01, also the validity is extended."))
        // two refunds can't both claim the same purchase
        val both = linkRefunds(listOf(amazon, fromShop(10, 17503.0, 20 * day), fromShop(12, 17503.0, 21 * day))) { it.sms != null }.associateBy { it.id }
        assertEquals(1L, both[10]!!.parentId); assertNull(both[12]!!.parentId)
    }

    @Test fun refundsComeOffSpending() {
        val today = LocalDate.of(2026, 11, 17)
        val all = listOf(
            Txn(1, 17503.0, true, Kind.SPENT, category = "Shopping", place = "Amazon India", time = at(today.minusDays(10))),
            Txn(2, 17483.0, false, Kind.REFUND, category = "Shopping", parentId = 1, time = at(today)),
            Txn(3, 500.0, true, Kind.SPENT, category = "Food", time = at(today)),
            Txn(4, 100.0, false, Kind.REFUND, category = "Food", time = at(today)), // couldn't find its purchase
        )
        val s = Stats(all, today)
        assertEquals(20.0 + 500.0 - 100.0, s.spentMonth, 0.001) // only the ₹20 fee of the Amazon order still counts
        assertEquals(listOf("Food" to 400.0, "Shopping" to 20.0), s.byCategory.map { it.first to Math.round(it.second * 100) / 100.0 })
        assertEquals(0.0, s.receivedMonth, 0.0) // refunds aren't income
        assertEquals(mapOf(1L to 17483.0), refundByParent(all))
    }

    @Test fun payeeRulesApplyToNewSmsFromThatPayee() {
        val toRamesh = "Your A/c XX1234 debited by Rs. 40.00 on 15/11/26; RAMESH KUMAR credited. RRN 1. Team IDFC FIRST Bank"
        val fromRamesh = "Your A/c XX1234 credited by Rs. 40.00 on 15/11/26 from RAMESH KUMAR. RRN 2. Team IDFC FIRST Bank"
        val plain = Sms.toTxn(bank, toRamesh, 0)!!
        assertEquals("out:ramesh kumar", plain.payeeKey())
        assertEquals(Kind.SPENT to "Other", plain.kind to plain.category) // no rule: just a guess
        val rules = mapOf("out:ramesh kumar" to (Kind.SPENT to "Food"))
        assertEquals("Food", Sms.toTxn(bank, toRamesh, 0, rules = rules)!!.category) // your choice wins
        assertEquals(Kind.RECEIVED, Sms.toTxn(bank, fromRamesh, 0, rules = rules)!!.kind) // money *from* them is untouched
        assertNull(Txn(amount = 1.0).payeeKey()) // nobody named → no rule
    }

    @Test fun equalSplits() {
        equalSplit(1200.0, listOf("Rahul", "Amit"), includeMe = true).let {
            assertEquals(400.0, it.each, 0.0); assertEquals(400.0, it.mine, 0.0); assertEquals(mapOf("Rahul" to 400.0, "Amit" to 400.0), it.shares)
        }
        equalSplit(1000.0, listOf("Rahul", "Amit"), includeMe = true).let {
            assertEquals(333.33, it.each, 0.0); assertEquals(333.34, it.mine, 0.001) // you take the leftover paisa
        }
        equalSplit(300.0, listOf("Rahul"), includeMe = false).let { assertEquals(300.0, it.each, 0.0); assertEquals(0.0, it.mine, 0.0) }
        equalSplit(0.29, listOf("A"), includeMe = false).let { assertEquals(0.29, it.each, 0.0) } // no float rounding loss
        assertEquals(Split(0.0, 500.0, emptyMap()), equalSplit(500.0, emptyList(), true))
        // only your share stays in spending once the loans exist
        val today = LocalDate.of(2026, 11, 17)
        val bill = Txn(1, 1200.0, true, Kind.SPENT, category = "Food", place = "Swiggy", time = at(today))
        val s = equalSplit(bill.amount, listOf("Rahul", "Amit"), true)
        val loans = s.shares.entries.mapIndexed { i, (n, a) -> Txn(10L + i, a, true, Kind.LENT, person = n, parentId = 1, time = bill.time) }
        val stats = Stats(listOf(bill) + loans, today)
        assertEquals(400.0, stats.spentMonth, 0.0); assertEquals(800.0, stats.owed, 0.0)
        assertEquals(listOf("Amit", "Rahul", "Sneha"), knownFriends(loans, setOf("Sneha", "rahul")).sorted()) // no duplicate Rahul
        // every screen uses the same "your share": Activity's Spent tile / day total add up to what Home shows
        val shares = Shares(listOf(bill) + loans)
        assertEquals(400.0, shares.net(bill), 0.0)
        assertEquals(400.0, (listOf(bill) + loans).forActivity().sumOf(shares::spending), 0.0)
        assertEquals(0.0, shares.spending(loans[0]), 0.0) // a friend's part is never your spending
        val rent = Txn(20, 38257.0, true, Kind.SPENT, category = "Rent", time = bill.time)
        assertEquals(0.0, Shares(listOf(rent)).spending(rent), 0.0)
    }

    private fun at(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 3_600_000

    @Test fun loansAndStats() {
        val today = LocalDate.of(2026, 11, 17)
        val all = listOf(
            Txn(1, 1000.0, true, Kind.LENT, person = "Rahul", time = at(today.minusDays(3))),
            Txn(2, 400.0, false, Kind.REPAY, loanId = 1, time = at(today)),
            Txn(3, 500.0, true, Kind.LENT, person = "Amit", time = at(today)),
            Txn(4, 500.0, false, Kind.REPAY, loanId = 3, time = at(today)),
            Txn(5, 300.0, true, Kind.SPENT, category = "Food", time = at(today)),
            Txn(6, 200.0, true, Kind.SPENT, category = "Bills", time = at(today.minusDays(1))),
            Txn(7, 999.0, true, Kind.SPENT, category = "Food", time = at(today.minusMonths(1))),
            Txn(8, 5000.0, true, Kind.INVEST, time = at(today)),
            Txn(9, 7000.0, true, Kind.INVEST, time = at(today.minusMonths(2))),
        )
        val open = openLoans(all)
        assertEquals(1, open.size) // Amit paid in full, so his loan is gone
        assertEquals(600.0, open[0].left, 0.0)
        val s = Stats(all, today)
        assertEquals(300.0, s.spentToday, 0.0)
        assertEquals(500.0, s.spentMonth, 0.0) // lending and investing are not "my spending"
        assertEquals(5000.0, s.investedMonth, 0.0)
        assertEquals(12000.0, s.investedTotal, 0.0)
        assertEquals(600.0, s.owed, 0.0)
        assertEquals(listOf("Food" to 300.0, "Bills" to 200.0), s.byCategory)
        val nov = java.time.YearMonth.of(2026, 11)
        assertEquals(1, all.filter(Filter(person = "rah", month = nov)::matches).size)
        assertEquals(3, all.filter(Filter(type = "Paid", month = nov, min = 250.0, max = 1000.0)::matches).size) // 1000 + 500 lent, 300 spent
        assertEquals(1, all.filter(Filter(month = null, category = "food", day = today.minusMonths(1))::matches).size)
    }
}
