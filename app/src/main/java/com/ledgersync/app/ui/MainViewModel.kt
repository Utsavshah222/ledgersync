package com.ledgersync.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ledgersync.app.data.AccountSummary
import com.ledgersync.app.data.AppDatabase
import com.ledgersync.app.data.BankAccount
import com.ledgersync.app.data.PeriodStats
import com.ledgersync.app.data.Settings
import com.ledgersync.app.data.Summary
import com.ledgersync.app.data.TxnEntity
import com.ledgersync.app.sms.Ingest
import com.ledgersync.app.sms.ParseResult
import com.ledgersync.app.sms.SmsParser
import com.ledgersync.app.sync.AppUpdate
import com.ledgersync.app.sync.SheetApi
import com.ledgersync.app.sync.UpdateChecker
import com.ledgersync.app.sync.SyncScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

data class ImportState(val running: Boolean, val scanned: Int = 0, val total: Int = 0, val added: Int = 0, val matched: Int = 0)

/** Dashboard data plus a flag telling whether it came from the sheet (all devices) or this phone only. */
data class DashboardData(val summary: Summary, val fromSheet: Boolean)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings.get(app)
    private val dao = AppDatabase.get(app).txns()
    private val wm = WorkManager.getInstance(app)

    val txns: StateFlow<List<TxnEntity>> = dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pending: StateFlow<Int> = dao.observePendingCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val failed: StateFlow<Int> = dao.observeFailedCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val accounts: StateFlow<List<BankAccount>> =
        settings.version.map { settings.accounts }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.accounts)

    val syncing: StateFlow<Boolean> = wm.getWorkInfosForUniqueWorkFlow(SyncScheduler.NOW)
        .map { list -> list.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val dashboard: StateFlow<DashboardData?> = combine(settings.version, accounts, txns) { _, accs, list ->
        val sheet = Summary.parse(settings.summaryJson)
        when {
            sheet != null && sheet.accounts.isNotEmpty() -> DashboardData(sheet, fromSheet = true)
            accs.isNotEmpty() -> DashboardData(localSummary(accs, list), fromSheet = false)
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _update = MutableStateFlow<AppUpdate?>(null)
    val update: StateFlow<AppUpdate?> = _update.asStateFlow()
    fun dismissUpdate() { _update.value = null }

    init {
        viewModelScope.launch {
            val current = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "0"
            _update.value = UpdateChecker.check(current)
        }
    }

    private val _import = MutableStateFlow<ImportState?>(null)
    val importState: StateFlow<ImportState?> = _import.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()
    fun toast(msg: String) { _events.tryEmit(msg) }

    private fun api(url: String = settings.scriptUrl, key: String = settings.secretKey) = SheetApi(url, key)

    fun syncNow(forceConfig: Boolean = true) {
        if (!settings.isConnected) { toast("Connect your Google Sheet first"); return }
        SyncScheduler.syncNow(getApplication(), forceConfig)
    }

    /** Validates the URL + key against the live script. Returns a human message. */
    suspend fun testConnection(url: String, key: String): Result<String> = runCatching {
        require(url.trim().startsWith("https://script.google.com/")) { "URL must start with https://script.google.com/" }
        require(key.isNotBlank()) { "Enter the secret key shown in the sheet" }
        val r = api(url.trim(), key.trim()).ping()
        "Connected to “${r.optString("sheet", "your sheet")}” · ${r.optInt("accounts")} accounts"
    }

    fun saveConnection(url: String, key: String, device: String) {
        settings.scriptUrl = url; settings.secretKey = key
        if (device.isNotBlank()) settings.deviceName = device
    }

    suspend fun refreshAccounts(): Result<Int> = runCatching {
        val list = api().fetchAccounts()
        settings.accountsJson = BankAccount.listToJson(list)
        settings.accountsFetchedAt = System.currentTimeMillis()
        runCatching { settings.summaryJson = api().fetchSummary() }
        list.size
    }

    suspend fun addAccount(acc: BankAccount): Result<Unit> = runCatching {
        require(acc.nickname.isNotBlank()) { "Give the account a short name" }
        require(acc.digits.length >= 4) { "Enter at least the last 4 digits of the account number" }
        require(accounts.value.none { it.nickname.equals(acc.nickname, true) }) { "An account with this name already exists" }
        api().addAccount(acc)
        refreshAccounts().getOrThrow()
        Unit
    }

    fun importHistory(fromMillis: Long, onDone: (ImportState) -> Unit = {}) {
        if (_import.value?.running == true) return
        viewModelScope.launch {
            _import.value = ImportState(true)
            val now = System.currentTimeMillis()
            val res = Ingest.scanInbox(getApplication(), fromMillis) { s, t -> _import.value = _import.value?.copy(scanned = s, total = t) }
            if (settings.lastInboxScan == 0L || settings.lastInboxScan > now) settings.lastInboxScan = now
            val done = ImportState(false, res.scanned, res.scanned, res.added, res.matched)
            _import.value = done
            onDone(done)
            if (res.added > 0) syncNow(false)
        }
    }

    fun parseTest(sender: String, body: String): String {
        val r = SmsParser.evaluate(sender.ifBlank { "VM-BANKSMS" }, body, accounts.value, settings.bankSendersOnly)
        val p = when (r) { is ParseResult.Ok -> r.parsed; is ParseResult.Skip -> r.parsed }
        val head = when (r) {
            is ParseResult.Ok -> "✅ WILL BE SAVED → ${r.account.nickname} (${r.account.bank} ${r.account.masked})"
            is ParseResult.Skip -> "⛔ IGNORED — ${r.reason}"
        }
        if (p == null) return head
        return buildString {
            appendLine(head); appendLine()
            appendLine("Type:      ${p.type}")
            appendLine("Amount:    ${formatInr(p.amount)}")
            appendLine("Balance:   ${p.balance?.let { formatInr(it) } ?: "—"}")
            appendLine("A/c found: ${p.accountTokens.joinToString().ifBlank { "—" }}")
            appendLine("Mode:      ${p.mode}")
            appendLine("Party:     ${p.party.ifBlank { "—" }}")
            appendLine("Reference: ${p.reference.ifBlank { "—" }}")
            append("Bank (ID): ${p.senderBank ?: "—"}")
        }
    }

    fun clearLocal() = viewModelScope.launch { dao.clear(); toast("Local history cleared — the sheet is untouched") }

    fun finishOnboarding() {
        settings.onboarded = true
        if (settings.lastInboxScan == 0L) settings.lastInboxScan = System.currentTimeMillis()
        syncNow(true)
    }

    companion object {
        fun startOfMonth(): Long = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun startOfYear(): Long = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun monthsAgo(n: Int): Long = Calendar.getInstance().apply {
            timeInMillis = startOfMonth(); add(Calendar.MONTH, -n)
        }.timeInMillis

        /** Offline fallback: totals from entries captured on this phone. */
        fun localSummary(accs: List<BankAccount>, list: List<TxnEntity>): Summary {
            val m = startOfMonth(); val y = startOfYear()
            fun stats(items: List<TxnEntity>) = items.fold(PeriodStats()) { acc, t ->
                acc + if (t.isCredit) PeriodStats(t.amount, 0.0, 1) else PeriodStats(0.0, t.amount, 1)
            }
            val by = list.groupBy { it.accountNick }
            return Summary(
                accounts = accs.filter { it.active }.map { a ->
                    val items = by[a.nickname].orEmpty()
                    val latest = items.filter { it.balance != null }.maxByOrNull { it.timestamp }
                    AccountSummary(
                        nickname = a.nickname, bank = a.bank, owner = a.owner, masked = a.masked,
                        balance = latest?.balance ?: a.balance, balanceAsOf = latest?.timestamp ?: a.balanceAsOf,
                        month = stats(items.filter { it.timestamp >= m }),
                        year = stats(items.filter { it.timestamp >= y }),
                        all = stats(items),
                    )
                },
                fetchedAt = System.currentTimeMillis(),
            )
        }
    }
}
