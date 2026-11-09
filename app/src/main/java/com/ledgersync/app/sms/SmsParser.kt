package com.ledgersync.app.sms

import com.ledgersync.app.data.BankAccount
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ParsedSms(
    val type: String, // CREDIT | DEBIT
    val amount: Double,
    val balance: Double?,
    val accountTokens: List<String>,
    val mode: String,
    val party: String,
    val reference: String,
    val senderBank: String?,
)

sealed class ParseResult {
    data class Ok(val parsed: ParsedSms, val account: BankAccount) : ParseResult()
    data class Skip(val reason: String, val parsed: ParsedSms? = null) : ParseResult()
}

/**
 * Parser for Indian bank transaction SMS (HDFC, SBI, ICICI, Axis, Kotak, PNB, BOB, Canara, Union,
 * IDFC, IndusInd, Yes, Federal and most others). Designed to be strict: a message is only accepted
 * when it is clearly a completed debit/credit AND it names one of the configured accounts.
 */
object SmsParser {
    private val IC = setOf(RegexOption.IGNORE_CASE)
    private const val CUR = """(?:(?<![a-z])rs\.?|(?<![a-z])inr|₹|rupees)"""
    private const val NUM = """(\d[\d,]*(?:\.\d+)?)"""

    // ---------- Rejection rules (OTP, promos, failed, reminders, requests) ----------
    private val REJECT = listOf(
        Regex("""\b(otp|one[\s-]?time[\s-]?password|verification code|cvv)\b""", IC) to "OTP / verification message",
        Regex("""\bwill be (debited|credited|deducted|charged)\b""", IC) to "Future / scheduled transaction",
        Regex("""\b(has requested|requested money|collect request|payment request|requesting)\b""", IC) to "Payment request (not a transaction)",
        Regex("""\b(amount due|amt due|total due|min(imum)?\.? (amt|amount) due|payment due|due date|is due|due on|due by)\b""", IC) to "Bill / due reminder",
        Regex("""\b(pre-?approved|eligible for|apply now|get a loan|loan offer|limited period offer)\b""", IC) to "Promotional message",
        Regex("""\b(mandate|standing instruction|si) (has been |is )?(created|registered|set up|scheduled)\b""", IC) to "Mandate setup (not a transaction)",
    )

    private val FAILED = Regex("""\b(failed|declined|unsuccessful|rejected|could not be processed)\b""", IC)
    /** A failed-txn SMS that says the money came back IS a real credit and must be kept. */
    private val MONEY_BACK = Regex("""\b(credited|reversed|refunded|refund)\b""", IC)

    // ---------- Transaction direction ----------
    private val DEBIT_WORDS = Regex(
        """\b(debited|debit(?!\s*card)|withdrawn|withdrawal|spent|sent|paid|purchase[d]?|deducted|transferred to|trf to|used (for|at)|txn of|charged)\b""", IC
    )
    private val CREDIT_WORDS = Regex(
        """\b(credited|credit(?!\s*card)|deposited|deposit|received|refund(ed)?|reversed|reversal|added to|cashback of)\b""", IC
    )
    private val DR_CR = Regex("""\b(dr|cr)\b\.?""", IC)

    // ---------- Amounts ----------
    private val BALANCE = Regex(
        """(?:\b(?:avl|avbl|available|avail|clr|clear|ledger|total|closing|updated|net|a/c)\.?\s*)?\bbal(?:ance)?\b\.?\s*(?:is|of|:|-|=|in a/c \S+ is)?\s*(?:$CUR)?\s*[:.\-]?\s*(-)?\s*$NUM""", IC
    )
    private val CUR_AMOUNT = Regex("""$CUR\s*[:.]?\s*$NUM""", IC)
    private val VERB_AMOUNT = Regex("""\b(?:debited|credited|withdrawn|deposited|paid|sent|spent|received)\s*(?:by|with|for|of)?\s*(?:$CUR)?\s*[:.]?\s*$NUM""", IC)

    // ---------- Account numbers ----------
    private val ACCT = Regex(
        """\b(?:a/c|a\.c\.?|acct|account|ac|card)\b\.?\s*(?:no\.?|number|num|ending(?:\s*(?:with|in))?)?\s*[:.\-]?\s*(?:[x*#•.]+\s*)?(\d{3,18})""", IC
    )
    private val MASKED = Regex("""(?:(?<![A-Za-z0-9])[xX]{1,}|[*#•]+)(\d{3,6})\b""")

    // ---------- Meta ----------
    private val VPA = Regex("""\b([a-zA-Z0-9.\-_]{2,}@[a-zA-Z]{2,})\b""")
    private val PARTY = Regex(
        """\b(?:to|from|by|at|towards|trf to|transfer to|transfer from|beneficiary)\s+(?:vpa\s+|m/s\.?\s+|mr\.?\s+|ms\.?\s+)?([A-Za-z0-9][A-Za-z0-9 .&'_\-/@]{1,40}?)(?=\s+(?:on|ref|via|upi|for|avl|is|dated|date|thru|through|with|in|has|value|bal|not)\b|[.;,(:]|\s*$)""", IC
    )
    private val ICICI_PARTY = Regex(""";\s*([A-Za-z][A-Za-z .&']{1,40}?)\s+credited""", IC)
    private val INFO = Regex("""\binfo[:\-]\s*([^.\n]{3,60})""", IC)
    private val PARTY_STOP = Regex("""^(your|you|the|a/c|ac\b|acct|account|card|bank|hdfc bank|sbi|icici|axis|kotak|xx|\*|\d|rs\b|inr|on\b|date)""", IC)
    private val REF = Regex(
        """\b(?:utr|rrn|ref(?:erence)?|txn|transaction|upi|imps|neft|rtgs)\s*(?:no|num|number|id|#)?\.?\s*[:.\-/]?\s*([A-Za-z0-9]{6,24})""", IC
    )

    private val MODES = listOf(
        Regex("""\bupi\b|@[a-z]{2,}\b|\bvpa\b""", IC) to "UPI",
        Regex("""\bimps\b""", IC) to "IMPS",
        Regex("""\bneft\b""", IC) to "NEFT",
        Regex("""\brtgs\b""", IC) to "RTGS",
        Regex("""\b(atm|cash withdrawal)\b""", IC) to "ATM",
        Regex("""\b(nach|ecs|auto-?debit|autopay|si\b|mandate)""", IC) to "AUTO-DEBIT",
        Regex("""\b(card|pos|spent)\b""", IC) to "CARD",
        Regex("""\b(cheque|chq|clg|clearing)\b""", IC) to "CHEQUE",
        Regex("""\b(cash dep|cash deposit|deposited by cash)\b""", IC) to "CASH",
        Regex("""\binterest\b""", IC) to "INTEREST",
        Regex("""\b(charges?|fee|gst)\b""", IC) to "CHARGES",
    )

    /** Sender ID code → bank name. Sender looks like "VM-HDFCBK", "AD-SBIINB-S", "JX-ICICIT". */
    private val BANK_CODES = linkedMapOf(
        "HDFC" to "HDFC Bank", "SBI" to "SBI", "CBSSBI" to "SBI", "ICICI" to "ICICI Bank", "AXIS" to "Axis Bank",
        "KOTAK" to "Kotak Bank", "KMB" to "Kotak Bank", "PNB" to "PNB", "BOB" to "Bank of Baroda", "BARODA" to "Bank of Baroda",
        "CANBNK" to "Canara Bank", "CANARA" to "Canara Bank", "UNION" to "Union Bank", "UBOI" to "Union Bank",
        "IDFC" to "IDFC First", "INDUS" to "IndusInd Bank", "YESB" to "Yes Bank", "FEDBNK" to "Federal Bank",
        "FEDERAL" to "Federal Bank", "RBL" to "RBL Bank", "AUBANK" to "AU Bank", "IDBI" to "IDBI Bank",
        "CENTBK" to "Central Bank", "CBOI" to "Central Bank", "IOB" to "Indian Overseas Bank", "UCO" to "UCO Bank",
        "BOI" to "Bank of India", "BOIIND" to "Bank of India", "INDBNK" to "Indian Bank", "BANDHN" to "Bandhan Bank",
        "SIBSMS" to "South Indian Bank", "KVB" to "Karur Vysya Bank", "CUB" to "City Union Bank", "DBS" to "DBS Bank",
        "SCBANK" to "Standard Chartered", "HSBC" to "HSBC", "CITI" to "Citibank", "PAYTM" to "Paytm Payments Bank",
        "AIRBNK" to "Airtel Payments Bank", "JKBANK" to "J&K Bank", "SARASWAT" to "Saraswat Bank", "EQUITAS" to "Equitas",
        "UJJIVN" to "Ujjivan", "DCB" to "DCB Bank", "TMB" to "TMB", "KBL" to "Karnataka Bank", "MAHABK" to "Bank of Maharashtra",
        "PSB" to "Punjab & Sind Bank",
    )

    fun bankFromSender(sender: String): String? {
        val s = sender.uppercase(Locale.ROOT)
        if (s.filter { it.isLetter() }.length < 3) return null
        return BANK_CODES.entries.firstOrNull { s.contains(it.key) }?.value
    }

    /** True for alphanumeric sender IDs like "VM-HDFCBK"; false for normal phone numbers. */
    fun isBusinessSender(sender: String): Boolean {
        val s = sender.replace(" ", "")
        if (s.isEmpty()) return false
        return s.any { it.isLetter() } || s.filter { it.isDigit() }.length in 3..6
    }

    fun parse(sender: String, rawBody: String): ParsedSms? {
        val body = rawBody.replace(Regex("""\s+"""), " ").trim()
        if (body.isEmpty()) return null

        // Direction: earliest strong keyword wins (e.g. "debited from A/c X ... credited to B" = debit).
        val d = DEBIT_WORDS.find(body)?.range?.first
        val c = CREDIT_WORDS.find(body)?.range?.first
        val type = when {
            d != null && c != null -> if (d <= c) "DEBIT" else "CREDIT"
            d != null -> "DEBIT"
            c != null -> "CREDIT"
            else -> DR_CR.find(body)?.groupValues?.get(1)?.lowercase()?.let { if (it == "dr") "DEBIT" else "CREDIT" }
        } ?: return null

        // Balance first, then exclude its span when looking for the transaction amount.
        val balMatch = BALANCE.find(body)
        val balance = balMatch?.let { m ->
            m.groupValues[2].toAmount()?.let { if (m.groupValues[1] == "-") -it else it }
        }
        val amount = CUR_AMOUNT.findAll(body)
            .filter { m -> balMatch == null || m.range.first !in balMatch.range }
            .mapNotNull { it.groupValues[1].toAmount() }
            .firstOrNull { it > 0 }
            ?: VERB_AMOUNT.find(body)?.groupValues?.get(1)?.toAmount()?.takeIf { it > 0 }
            ?: return null

        // Ordered by position: the bank names YOUR account first, the other party's account later.
        val tokens = (ACCT.findAll(body).map { it.range.first to it.groupValues[1] } +
            MASKED.findAll(body).map { it.range.first to it.groupValues[1] })
            .sortedBy { it.first }.map { it.second }.distinct().toList()

        val mode = MODES.firstOrNull { it.first.containsMatchIn(body) }?.second ?: "TRANSFER"

        val reference = REF.findAll(body).map { it.groupValues[1] }
            .firstOrNull { r -> r.count { it.isDigit() } >= 5 } ?: ""

        val party = extractParty(body, type)

        return ParsedSms(type, amount, balance, tokens, mode, party, reference, bankFromSender(sender))
    }

    private fun extractParty(body: String, type: String): String {
        VPA.find(body)?.let { return it.groupValues[1] }
        if (type == "DEBIT") ICICI_PARTY.find(body)?.let { return clean(it.groupValues[1]) }
        PARTY.findAll(body).map { clean(it.groupValues[1]) }
            .firstOrNull { it.length >= 3 && !PARTY_STOP.containsMatchIn(it) }?.let { return it }
        INFO.find(body)?.let { return clean(it.groupValues[1]) }
        return ""
    }

    private fun clean(s: String) = s.trim().trim('.', '-', ',', ';', ':').replace(Regex("""\s+"""), " ").take(48)

    private fun String.toAmount(): Double? = replace(",", "").toDoubleOrNull()

    fun matchAccount(p: ParsedSms, accounts: List<BankAccount>): BankAccount? {
        val active = accounts.filter { it.active && it.digits.length >= 3 }
        // Walk tokens in message order; the first token that names a tracked account decides.
        for (tk in p.accountTokens) {
            val scored = active.map { it to score(it, tk) }.filter { it.second > 0 }
            if (scored.isEmpty()) continue
            val top = scored.maxOf { it.second }
            val tied = scored.filter { it.second == top }.map { it.first }
            if (tied.size == 1) return tied.first()
            // Same last digits at two banks → use the sender ID to break the tie.
            val sb = p.senderBank?.lowercase() ?: return tied.first()
            return tied.firstOrNull { sameBank(it.bank, sb) } ?: tied.first()
        }
        return null
    }

    private fun score(acc: BankAccount, token: String): Int {
        if (token.length < 3) return 0
        val d = acc.digits
        if (d == token) return 100
        if (d.endsWith(token)) return token.length * 10
        if (token.length >= 6 && token.endsWith(d.takeLast(6))) return 60
        if (acc.aliases.any { al -> al.length >= 3 && (al == token || al.endsWith(token) || token.endsWith(al)) }) return 35
        return 0
    }

    private fun sameBank(configured: String, senderBank: String): Boolean {
        val a = configured.lowercase().replace("bank", "").trim()
        val b = senderBank.replace("bank", "").trim()
        return a.isNotEmpty() && (a.contains(b) || b.contains(a))
    }

    /** Full decision used by the receiver, the inbox scanner and the in-app Parser Lab. */
    fun evaluate(sender: String, body: String, accounts: List<BankAccount>, bankSendersOnly: Boolean): ParseResult {
        if (bankSendersOnly && !isBusinessSender(sender)) return ParseResult.Skip("Sender is a personal number, not a bank")
        REJECT.firstOrNull { it.first.containsMatchIn(body) }?.let { return ParseResult.Skip(it.second) }
        if (FAILED.containsMatchIn(body) && !MONEY_BACK.containsMatchIn(body)) return ParseResult.Skip("Failed / declined transaction")
        val p = parse(sender, body) ?: return ParseResult.Skip("Not a debit/credit message")
        if (accounts.none { it.active }) return ParseResult.Skip("No accounts configured yet", p)
        val acc = matchAccount(p, accounts)
            ?: return ParseResult.Skip(
                if (p.accountTokens.isEmpty()) "No account number in message" else "Account ${p.accountTokens.joinToString()} is not in the tracked list", p
            )
        return ParseResult.Ok(p, acc)
    }

    /** Stable ID shared by every device that receives this SMS, so the sheet never gets duplicates. */
    fun txnKey(acc: BankAccount, p: ParsedSms, body: String, ts: Long): String {
        val amt = String.format(Locale.ROOT, "%.2f", p.amount)
        val base = if (p.reference.length >= 6) {
            "${acc.digits}|${p.type}|$amt|${p.reference.uppercase()}"
        } else {
            val day = SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(ts))
            "${acc.digits}|${p.type}|$amt|${body.lowercase().replace(Regex("""\s+"""), " ").trim()}|$day"
        }
        val md = MessageDigest.getInstance("SHA-1").digest(base.toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(20)
    }
}
