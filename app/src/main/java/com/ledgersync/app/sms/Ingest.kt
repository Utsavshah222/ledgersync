package com.ledgersync.app.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.ledgersync.app.Notifications
import com.ledgersync.app.data.AppDatabase
import com.ledgersync.app.data.Settings
import com.ledgersync.app.data.TxnEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ScanResult(val scanned: Int, val matched: Int, val added: Int)

object Ingest {

    /** Parse one SMS; store it if it's a tracked-account transaction. Returns true if newly added. */
    suspend fun ingest(ctx: Context, sender: String, body: String, ts: Long, notify: Boolean): Boolean {
        val settings = Settings.get(ctx)
        val res = SmsParser.evaluate(sender, body, settings.accounts, settings.bankSendersOnly)
        if (res !is ParseResult.Ok) return false
        val p = res.parsed
        val acc = res.account
        val entity = TxnEntity(
            txnKey = SmsParser.txnKey(acc, p, body, ts),
            timestamp = ts,
            accountNick = acc.nickname,
            bank = acc.bank.ifBlank { p.senderBank ?: "" },
            accountMasked = acc.masked,
            owner = acc.owner,
            type = p.type,
            amount = p.amount,
            balance = p.balance,
            mode = p.mode,
            party = p.party,
            reference = p.reference,
            sender = sender,
            body = body,
        )
        val added = AppDatabase.get(ctx).txns().insert(entity) != -1L
        if (added && notify && settings.notifyOnCapture) Notifications.showTxn(ctx, entity)
        return added
    }

    fun hasReadSms(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** Reads the SMS inbox from [fromMillis] onwards. Used for history import and as a safety net. */
    suspend fun scanInbox(ctx: Context, fromMillis: Long, onProgress: (Int, Int) -> Unit = { _, _ -> }): ScanResult =
        withContext(Dispatchers.IO) {
            if (!hasReadSms(ctx)) return@withContext ScanResult(0, 0, 0)
            val settings = Settings.get(ctx)
            val accounts = settings.accounts
            var scanned = 0; var matched = 0; var added = 0
            val cols = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT)
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI, cols,
                "${Telephony.Sms.DATE} >= ?", arrayOf(fromMillis.toString()),
                "${Telephony.Sms.DATE} ASC"
            )?.use { c ->
                val total = c.count
                while (c.moveToNext()) {
                    scanned++
                    val sender = c.getString(0) ?: ""
                    val body = c.getString(1) ?: ""
                    val date = c.getLong(2)
                    val sent = c.getLong(3)
                    val ts = if (sent > 0) sent else date
                    if (SmsParser.evaluate(sender, body, accounts, settings.bankSendersOnly) is ParseResult.Ok) {
                        matched++
                        if (ingest(ctx, sender, body, ts, notify = false)) added++
                    }
                    if (scanned % 50 == 0) onProgress(scanned, total)
                }
                onProgress(scanned, total)
            }
            ScanResult(scanned, matched, added)
        }
}
