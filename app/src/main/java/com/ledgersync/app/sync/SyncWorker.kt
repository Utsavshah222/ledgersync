package com.ledgersync.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ledgersync.app.Notifications
import com.ledgersync.app.data.AppDatabase
import com.ledgersync.app.data.HistoryCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.ledgersync.app.data.Settings
import com.ledgersync.app.data.SyncState
import com.ledgersync.app.sms.Ingest
import java.util.concurrent.TimeUnit

/**
 * One pass of the pipeline:
 *  1. refresh the tracked-account list from the sheet (every 3h, or when forced)
 *  2. re-scan the SMS inbox since the last scan (safety net for OEMs that kill background receivers)
 *  3. upload every pending entry in batches; the sheet de-duplicates across devices
 *  4. refresh the dashboard numbers
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    // One sync at a time: the "sync now" and the 15-minute jobs must never upload the same batch twice.
    override suspend fun doWork(): Result = runLock.withLock { sync() }

    private suspend fun sync(): Result {
        val ctx = applicationContext
        val settings = Settings.get(ctx)
        if (!settings.isConnected) return Result.success()
        val api = SheetApi(settings.scriptUrl, settings.secretKey)
        val dao = AppDatabase.get(ctx).txns()
        val force = inputData.getBoolean(KEY_FORCE_CONFIG, false)

        try {
            if (force || System.currentTimeMillis() - settings.accountsFetchedAt > TimeUnit.HOURS.toMillis(3)) {
                val accounts = api.fetchAccounts()
                settings.accountsJson = com.ledgersync.app.data.BankAccount.listToJson(accounts)
                settings.accountsFetchedAt = System.currentTimeMillis()
            }
        } catch (_: Exception) { /* keep the cached list */ }

        if (Ingest.hasReadSms(ctx) && settings.lastInboxScan > 0) {
            val from = settings.lastInboxScan - TimeUnit.HOURS.toMillis(6) // overlap; duplicates are ignored
            val now = System.currentTimeMillis()
            runCatching { Ingest.scanInbox(ctx, from) }.onSuccess { settings.lastInboxScan = now }
        }

        var failed = false
        var uploaded = false
        while (true) {
            val batch = dao.pending(BATCH)
            if (batch.isEmpty()) break
            try {
                val results = api.push(settings.deviceName, batch)
                uploaded = true
                val now = System.currentTimeMillis()
                val added = batch.filter { results[it.txnKey] == "added" }.map { it.txnKey }
                val dup = batch.filter { results[it.txnKey] == "duplicate" }.map { it.txnKey }
                val unknown = batch.map { it.txnKey } - added.toSet() - dup.toSet()
                if (added.isNotEmpty()) dao.mark(added, SyncState.SYNCED, now)
                if (dup.isNotEmpty()) dao.mark(dup, SyncState.DUPLICATE, now)
                if (unknown.isNotEmpty()) { dao.markFailed(unknown, "Sheet did not confirm"); failed = true; break }
            } catch (e: Exception) {
                dao.markFailed(batch.map { it.txnKey }, e.message ?: "Upload failed")
                settings.lastSyncError = e.message
                failed = true
                break
            }
        }

        // Balances + newest history from every phone. Skipped when nothing changed and it was fetched moments ago
        // (several queued "sync now" runs back-to-back after the phone comes back online).
        val now = System.currentTimeMillis()
        if (force || uploaded || now - lastHomeFetch > TimeUnit.SECONDS.toMillis(30)) {
            runCatching { refreshHome(ctx, api) }.onSuccess { lastHomeFetch = now }
        }

        return if (failed) {
            if (runAttemptCount >= 3) Notifications.showSyncProblem(ctx, settings.lastSyncError ?: "Upload failed")
            Result.retry()
        } else {
            settings.lastSyncAt = System.currentTimeMillis()
            settings.lastSyncError = null
            Notifications.clearSyncProblem(ctx)
            Result.success()
        }
    }

    companion object {
        const val KEY_FORCE_CONFIG = "forceConfig"
        private const val BATCH = 100
        private val runLock = Mutex()
        @Volatile private var lastHomeFetch = 0L

        /** Saves the sheet's balances/totals and its first history page (all phones) for instant, offline-ready screens. */
        suspend fun refreshHome(ctx: Context, api: SheetApi) {
            val (summary, recent) = api.fetchHome()
            recent?.let { HistoryCache.write(ctx, HistoryCache.DEFAULT, it) }
            Settings.get(ctx).summaryJson = summary
        }
    }
}

object SyncScheduler {
    const val NOW = "sync-now"
    private const val PERIODIC = "sync-periodic"

    private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun syncNow(ctx: Context, forceConfig: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(net)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(androidx.work.workDataOf(SyncWorker.KEY_FORCE_CONFIG to forceConfig))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(net)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SyncScheduler.schedulePeriodic(context)
        SyncScheduler.syncNow(context)
    }
}
