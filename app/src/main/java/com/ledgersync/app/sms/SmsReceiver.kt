package com.ledgersync.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.ledgersync.app.sync.SyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Wakes up for every incoming SMS (even when the app is closed), stores matches, triggers upload. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        // Long bank SMS arrive in several parts — stitch them back together per sender.
        val bySender = messages.filterNotNull().groupBy { it.originatingAddress ?: it.displayOriginatingAddress ?: "" }
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                var any = false
                bySender.forEach { (sender, parts) ->
                    val body = parts.joinToString("") { it.messageBody ?: "" }
                    val ts = parts.first().timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
                    if (Ingest.ingest(app, sender, body, ts, notify = true)) any = true
                }
                if (any) SyncScheduler.syncNow(app)
            } catch (_: Throwable) {
                // Never crash in a receiver; the periodic inbox re-scan will pick it up.
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
