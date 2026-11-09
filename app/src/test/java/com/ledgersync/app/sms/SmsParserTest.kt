package com.ledgersync.app.sms

import com.ledgersync.app.data.BankAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsParserTest {

    private val accounts = listOf(
        BankAccount("HDFC Current", "HDFC Bank", "50200012341234", "Company", aliases = listOf("9876")),
        BankAccount("SBI Main", "SBI", "38123456785678", "Company"),
        BankAccount("ICICI Ops", "ICICI Bank", "000401004321", "Company"),
        BankAccount("Axis Payroll", "Axis Bank", "917020011112222", "Company"),
        BankAccount("Kotak Owner", "Kotak Bank", "1234567890", "Personal"),
        BankAccount("BOB Collections", "Bank of Baroda", "12340100009999", "Company"),
    )

    private fun ok(sender: String, body: String): ParseResult.Ok {
        val r = SmsParser.evaluate(sender, body, accounts, bankSendersOnly = true)
        assertTrue("Expected OK but got $r for: $body", r is ParseResult.Ok)
        return r as ParseResult.Ok
    }

    private fun skip(sender: String, body: String) {
        val r = SmsParser.evaluate(sender, body, accounts, bankSendersOnly = true)
        assertTrue("Expected SKIP but got $r for: $body", r is ParseResult.Skip)
    }

    @Test fun hdfcUpiSent() {
        val r = ok("VM-HDFCBK", "Sent Rs.1,250.00 From HDFC Bank A/C *1234 To RAHUL KUMAR On 10/11/26 Ref 631498765432 Not You? Call 18002586161/SMS BLOCK UPI to 7308080808")
        assertEquals("HDFC Current", r.account.nickname)
        assertEquals("DEBIT", r.parsed.type)
        assertEquals(1250.0, r.parsed.amount, 0.001)
        assertEquals("631498765432", r.parsed.reference)
        assertEquals("RAHUL KUMAR", r.parsed.party)
        assertEquals("UPI", r.parsed.mode)
    }

    @Test fun hdfcCreditWithBalance() {
        val r = ok("AD-HDFCBK", "Update! INR 45,000.00 deposited in HDFC Bank A/c XX1234 on 10-NOV-26 for NEFT Cr-ICIC0000001-ACME TRADERS-HDFC-N312345678. Avl bal INR 3,21,456.78. Cheque deposits in A/C are subject to clearing")
        assertEquals("CREDIT", r.parsed.type)
        assertEquals(45000.0, r.parsed.amount, 0.001)
        assertEquals(321456.78, r.parsed.balance!!, 0.001)
        assertEquals("NEFT", r.parsed.mode)
    }

    @Test fun sbiUpiDebitWithoutCurrencySymbol() {
        val r = ok("JD-SBIUPI", "Dear UPI user A/C X5678 debited by 20.0 on date 10Nov26 trf to SHARMA STORE Refno 631412345678. If not u? call 1800111109. -SBI")
        assertEquals("SBI Main", r.account.nickname)
        assertEquals("DEBIT", r.parsed.type)
        assertEquals(20.0, r.parsed.amount, 0.001)
        assertEquals("631412345678", r.parsed.reference)
    }

    @Test fun sbiCredit() {
        val r = ok("VM-SBIINB", "Dear Customer, Your A/C XXXXX785678 has been credited with INR 12,500.00 on 10/11/2026 by transfer from ACME LTD. Avl Bal INR 88,000.50 -SBI")
        assertEquals("SBI Main", r.account.nickname)
        assertEquals("CREDIT", r.parsed.type)
        assertEquals(12500.0, r.parsed.amount, 0.001)
        assertEquals(88000.50, r.parsed.balance!!, 0.001)
    }

    @Test fun iciciDebitWithPartyAfterSemicolon() {
        val r = ok("VK-ICICIT", "ICICI Bank Acct XX321 debited for Rs 5,000.00 on 10-Nov-26; ZOMATO LTD credited. UPI:631456789012. Call 18002662 for dispute. SMS BLOCK 321 to 9215676766.")
        assertEquals("ICICI Ops", r.account.nickname)
        assertEquals("DEBIT", r.parsed.type)
        assertEquals(5000.0, r.parsed.amount, 0.001)
        assertEquals("ZOMATO LTD", r.parsed.party)
        assertEquals("631456789012", r.parsed.reference)
    }

    @Test fun axisImpsCredit() {
        val r = ok("AX-AXISBK", "INR 75,000.00 credited to A/c no. XX2222 on 10-11-26 at 14:22:10 IST. Info- IMPS/P2A/631499887766/GLOBALEXPORTS. Avl Bal- INR 2,10,000.00 - Axis Bank")
        assertEquals("Axis Payroll", r.account.nickname)
        assertEquals("CREDIT", r.parsed.type)
        assertEquals(75000.0, r.parsed.amount, 0.001)
        assertEquals(210000.0, r.parsed.balance!!, 0.001)
        assertEquals("IMPS", r.parsed.mode)
    }

    @Test fun kotakSentToVpa() {
        val r = ok("VM-KOTAKB", "Sent Rs.499.00 from Kotak Bank AC X7890 to amazon@apl on 10-11-26.UPI Ref 631477788899. Not you, https://kotak.com/KBANKT/Fraud")
        assertEquals("Kotak Owner", r.account.nickname)
        assertEquals("Personal", r.account.owner)
        assertEquals(499.0, r.parsed.amount, 0.001)
        assertEquals("amazon@apl", r.parsed.party)
    }

    @Test fun debitCardSpendMapsToLinkedAccount() {
        val r = ok("VM-HDFCBK", "Rs.2,340.00 spent on HDFC Bank Card x9876 at RELIANCE SMART on 2026-11-10:18:42:11.Not You? To Block+Reissue Call 18002586161")
        assertEquals("HDFC Current", r.account.nickname)
        assertEquals("DEBIT", r.parsed.type)
        assertEquals("CARD", r.parsed.mode)
        assertEquals("RELIANCE SMART", r.parsed.party)
    }

    @Test fun atmWithdrawal() {
        val r = ok("BP-BOBTXN", "Rs.10000 withdrawn at ATM from A/c ...9999 on 10-11-2026. Avl Bal Rs.55,000.00 - Bank of Baroda")
        assertEquals("BOB Collections", r.account.nickname)
        assertEquals("ATM", r.parsed.mode)
        assertEquals(10000.0, r.parsed.amount, 0.001)
        assertEquals(55000.0, r.parsed.balance!!, 0.001)
    }

    @Test fun transferBetweenOwnAccountsUsesFirstAccount() {
        val r = ok("VM-HDFCBK", "Rs 1,00,000.00 debited from A/c XX1234 to A/c XX5678 on 10-11-26 NEFT Ref N31298765. Avl bal Rs 50,000.00")
        assertEquals("HDFC Current", r.account.nickname)
        assertEquals("DEBIT", r.parsed.type)
        assertEquals(100000.0, r.parsed.amount, 0.001)
    }

    @Test fun refundAfterFailureIsKeptAsCredit() {
        val r = ok("VM-HDFCBK", "Rs.500.00 credited back to HDFC Bank A/c XX1234 for failed UPI txn Ref 631400011122 on 10-11-26")
        assertEquals("CREDIT", r.parsed.type)
    }

    @Test fun onlyLast4DigitsConfiguredStillMatchesLongerMask() {
        val short = listOf(BankAccount("Canara", "Canara Bank", "4455", "Company"))
        val r = SmsParser.evaluate("VM-CANBNK", "An amount of INR 3,000.00 has been CREDITED to your account XXXXX224455 on 10/11/2026 towards UPI. Total Avail.bal INR 9,000.00 - Canara Bank", short, true)
        assertTrue(r is ParseResult.Ok)
        assertEquals(3000.0, (r as ParseResult.Ok).parsed.amount, 0.001)
    }

    @Test fun mrsIsNotCurrency() {
        val r = ok("VM-HDFCBK", "Mrs.Sharma, Rs.800.00 debited from A/c XX1234 on 10-11-26 UPI Ref 631400000077")
        assertEquals(800.0, r.parsed.amount, 0.001)
    }

    // ---------------- rejections ----------------

    @Test fun otpIsIgnored() = skip("VM-HDFCBK", "OTP for txn of Rs 5,000.00 on HDFC Bank card XX1234 is 482913. Valid for 5 mins. Do not share.")
    @Test fun failedIsIgnored() = skip("VM-HDFCBK", "Txn of Rs 2,000 from A/c XX1234 failed due to insufficient balance.")
    @Test fun collectRequestIsIgnored() = skip("VM-HDFCBK", "RAMESH has requested money from you Rs 3,000 on HDFC Bank UPI. A/c XX1234. On approving, amount will be debited.")
    @Test fun scheduledIsIgnored() = skip("VM-HDFCBK", "Rs 1,999 will be debited from A/c XX1234 on 15-11-26 towards NETFLIX autopay.")
    @Test fun billReminderIsIgnored() = skip("VM-SBICRD", "Total amount due Rs 24,500 on A/c XX5678. Payment due date 20-11-26.")
    @Test fun loanPromoIsIgnored() = skip("VM-HDFCBK", "You are eligible for a pre-approved loan of Rs 5,00,000 credited instantly to A/c XX1234!")
    @Test fun untrackedAccountIsIgnored() = skip("VM-HDFCBK", "Rs.500.00 debited from A/c XX4444 on 10-11-26. Avl bal Rs 1,000.00")
    @Test fun personalNumberSenderIsIgnored() = skip("+919876543210", "Rs.500.00 debited from A/c XX1234 on 10-11-26.")
    @Test fun normalChatIsIgnored() = skip("VM-HDFCBK", "Dear customer, our branch will remain closed on Monday.")

    // ---------------- dedupe keys ----------------

    @Test fun sameSmsOnTwoPhonesGivesSameKey() {
        val body = "Sent Rs.1,250.00 From HDFC Bank A/C *1234 To RAHUL KUMAR On 10/11/26 Ref 631498765432"
        val r = ok("VM-HDFCBK", body)
        val k1 = SmsParser.txnKey(r.account, r.parsed, body, 1_762_760_000_000)
        val k2 = SmsParser.txnKey(r.account, r.parsed, "  $body ", 1_762_760_009_000)
        assertEquals(k1, k2)
    }

    @Test fun differentTxnsGiveDifferentKeys() {
        val a = ok("VM-HDFCBK", "Sent Rs.100.00 From HDFC Bank A/C *1234 To A On 10/11/26 Ref 631400000001")
        val b = ok("VM-HDFCBK", "Sent Rs.100.00 From HDFC Bank A/C *1234 To A On 10/11/26 Ref 631400000002")
        assertNotEquals(
            SmsParser.txnKey(a.account, a.parsed, "x", 0),
            SmsParser.txnKey(b.account, b.parsed, "x", 0),
        )
    }

    @Test fun bankFromSender() {
        assertEquals("HDFC Bank", SmsParser.bankFromSender("VM-HDFCBK"))
        assertEquals("SBI", SmsParser.bankFromSender("AD-SBIINB-S"))
        assertNull(SmsParser.bankFromSender("+919999999999"))
    }
}
