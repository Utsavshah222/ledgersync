package com.ledgersync.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ledgersync.app.data.TxnEntity
import com.ledgersync.app.ui.MainActivity
import com.ledgersync.app.ui.formatInr

object Notifications {
    const val CH_TXN = "txn"
    const val CH_STATUS = "status"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_TXN, "Captured transactions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A bank SMS was captured and queued for Google Sheets"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Sync problems", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown when uploads to Google Sheets keep failing"
            }
        )
    }

    private fun canPost(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < 33

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun showTxn(ctx: Context, t: TxnEntity) {
        if (!canPost(ctx)) return
        val sign = if (t.isCredit) "+" else "−"
        val title = "$sign${formatInr(t.amount)} · ${t.accountNick}"
        val text = listOf(if (t.isCredit) "Credited" else "Debited", t.party.takeIf { it.isNotBlank() }, t.mode)
            .filterNotNull().joinToString(" · ")
        val n = NotificationCompat.Builder(ctx, CH_TXN)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setColor(if (t.isCredit) 0xFF22C55E.toInt() else 0xFFFF5C7A.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setSubText("Saved to sheet queue")
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .setGroup("txns")
            .build()
        try { NotificationManagerCompat.from(ctx).notify(t.txnKey.hashCode(), n) } catch (_: SecurityException) {}
    }

    fun showSyncProblem(ctx: Context, message: String) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("Couldn't reach Google Sheets")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message\nEntries are safe on this phone and will upload automatically."))
            .setContentIntent(openApp(ctx))
            .setOnlyAlertOnce(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(9001, n) } catch (_: SecurityException) {}
    }

    fun clearSyncProblem(ctx: Context) = NotificationManagerCompat.from(ctx).cancel(9001)
}
