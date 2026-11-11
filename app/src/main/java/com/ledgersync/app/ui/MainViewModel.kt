package com.ledgersync.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.ledgersync.app.data.AccountSummary
import com.ledgersync.app.data.AppDatabase
import com.ledgersync.app.data.BankAccount
import com.ledgersync.app.data.HistoryCache
import com.ledgersync.app.data.LocalTotals
import com.ledgersync.app.data.PeriodStats
import com.ledgersync.app.data.Settings
import com.ledgersync.app.data.SheetTxn
import com.ledgersync.app.data.Summary
import com.ledgersync.app.data.SyncState
import com.ledgersync.app.data.TxnEntity
import com.ledgersync.app.sms.Ingest
import com.ledgersync.app.sms.ParseResult
import com.ledgersync.app.sms.SmsParser
import com.ledgersync.app.sync.AppUpdate
import com.ledgersync.app.sync.SheetApi
import com.ledgersync.app.sync.SyncScheduler
import com.ledgersync.app.sync.SyncWorker
import com.ledgersync.app.sync.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class ImportState(val running: Boolean, val scanned: Int = 0, val total: Int = 0, val added: Int = 0, val matched: Int = 0)

/** Dashboard data plus a flag telling whether it came from the sheet (all devices) or this phone only. */
data class DashboardData(val summary: Summary, val fromSheet: Boolean)

/** Where the Activity list reads from: every phone (the Google Sheet) or only what this phone captured. */
enum class HistorySource(val label: String) { SHEET("All devices"), PHONE("This phone") }

data class HistoryFilter(val account: String? = null, val type: String? = null, val query: String = "", val unsynced: Boolean = false) {
    fun matches(t: TxnEntity): Boolean =
        (account == null || t.accountNick == account) && (type == null || t.type == type) &&
            (!unsynced || t.syncState == SyncState.PENDING || t.syncState == SyncState.FAILED) &&
            (query.isBlank() || listOf(t.party, t.accountNick, t.mode, t.reference, t.body, t.amount.toString())
                .any { it.contains(query.trim(), ignoreCase = true) })
}

/** Paged history from the sheet. [cached] = the saved copy is on screen while a fresh one loads (or the phone is offline). */
data class SheetFeed(
    val items: List<SheetTxn> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextOffset: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    val cached: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings.get(app)
    private val dao = AppDatabase.get(app).txns()
    private val wm = WorkManager.getInstance(app)

    /** Newest entries captured on this phone (Home shows a handful; Activity pages through the rest). */
    val recentLocal: StateFlow<List<TxnEntity>> = dao.observeRecent(RECENT).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pending: StateFlow<Int> = dao.observePendingCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val failed: StateFlow<Int> = dao.observeFailedCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val accounts: StateFlow<List<BankAccount>> =
        settings.version.map { settings.accounts }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.accounts)

    val syncing: StateFlow<Boolean> = wm.getWorkInfosForUniqueWorkFlow(SyncScheduler.NOW)
        .map { list -> list.any { it.state == WorkInfo.State.RUNNING } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** The last summary downloaded from the sheet: balances and totals of every account, from all phones. */
    private val sheetSummary: StateFlow<Summary?> = settings.version.map { settings.summaryJson }.distinctUntilChanged()
        .map { json -> withContext(Dispatchers.Default) { parseSummary(json) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, parseSummary(settings.summaryJson))

    /** Local entries the sheet summary doesn't contain yet (queued offline, or uploaded after it was fetched). */
    private val unsettled: StateFlow<List<TxnEntity>> = sheetSummary.map { it?.fetchedAt ?: Long.MAX_VALUE }.distinctUntilChanged()
        .flatMapLatest { since -> dao.observeUnsettled(since) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The whole local table is only read when there is no sheet summary at all (first run / never connected). */
    private val fallbackTxns: Flow<List<TxnEntity>> = sheetSummary.map { it == null }.distinctUntilChanged()
        .flatMapLatest { none -> if (none) dao.observeAll() else flowOf(emptyList()) }

    val dashboard: StateFlow<DashboardData?> = combine(sheetSummary, accounts, unsettled, fallbackTxns) { sheet, accs, extra, all ->
        withContext(Dispatchers.Default) {
            when {
                sheet != null -> DashboardData(withLocal(sheet, extra), fromSheet = true)
                accs.isNotEmpty() -> DashboardData(localSummary(accs, all), fromSheet = false)
                else -> null
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Entries waiting on this phone for internet — shown on top of the all-devices list. */
    val queued: StateFlow<List<TxnEntity>> =
        unsettled.map { l -> l.filter { it.syncState == SyncState.PENDING || it.syncState == SyncState.FAILED } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ------------------------------------------------------------------ history (Activity screen)

    private val _source = MutableStateFlow(HistorySource.SHEET)
    val historySource: StateFlow<HistorySource> = _source.asStateFlow()
    private val _filter = MutableStateFlow(HistoryFilter())
    val historyFilter: StateFlow<HistoryFilter> = _filter.asStateFlow()

    private val _feed = MutableStateFlow(SheetFeed())
    val feed: StateFlow<SheetFeed> = _feed.asStateFlow()
    private var feedJob: Job? = null
    private var feedGen = 0

    /** Newest entries from all phones for the Home screen; read from disk instantly, refreshed by every sync. */
    private val _recentShared = MutableStateFlow<List<SheetTxn>>(emptyList())
    val recentShared: StateFlow<List<SheetTxn>> = _recentShared.asStateFlow()

    private val localPages = MutableStateFlow(1)
    val localItems: StateFlow<List<TxnEntity>> = combine(_filter, localPages) { f, p -> f to p }
        .flatMapLatest { (f, p) -> dao.observePage(f.account, f.type, f.unsynced, f.query.trim(), p * PAGE) }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val localTotals: StateFlow<LocalTotals> = _filter
        .flatMapLatest { f -> dao.observeTotals(f.account, f.type, f.unsynced, f.query.trim()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, LocalTotals(0, 0.0, 0.0))

    fun loadMoreLocal() {
        if (localTotals.value.count > localItems.value.size && localItems.value.size >= localPages.value * PAGE) localPages.value++
    }

    fun setHistorySource(s: HistorySource) {
        _source.value = s
        if (s == HistorySource.SHEET && _filter.value.unsynced) setFilter(_filter.value.copy(unsynced = false))
    }

    fun setFilter(f: HistoryFilter) {
        if (f == _filter.value) return
        val onlyQueryChanged = f.copy(query = "") == _filter.value.copy(query = "")
        val sheetChanged = f.copy(unsynced = false) != _filter.value.copy(unsynced = false)
        _filter.value = f
        localPages.value = 1
        if (sheetChanged) reloadFeed(keepItems = onlyQueryChanged)
    }

    /** Shows the saved first page immediately, then fetches a fresh one. */
    fun reloadFeed(keepItems: Boolean = false) {
        feedJob?.cancel()
        val f = _filter.value
        val gen = ++feedGen
        val cacheKey = if (f.query.isBlank()) HistoryCache.key(f.account, f.type) else null
        val cached = cacheKey?.let { HistoryCache.read(getApplication(), it) }
        _feed.value = when {
            cached != null -> SheetFeed(cached.items, loading = true, hasMore = cached.hasMore, nextOffset = cached.nextOffset, total = cached.total, cached = true)
            keepItems -> _feed.value.copy(loading = true, loadingMore = false, error = null)
            else -> SheetFeed(loading = true)
        }
        if (!settings.isConnected) {
            _feed.update { it.copy(loading = false, error = "Connect your Google Sheet in Settings") }
            return
        }
        feedJob = viewModelScope.launch {
            if (f.query.isNotBlank()) delay(350) // wait until typing pauses
            try {
                val (page, raw) = api().fetchHistory(f.account, f.type, f.query, 0)
                if (gen != feedGen) return@launch
                cacheKey?.let { HistoryCache.write(getApplication(), it, raw) }
                if (cacheKey == HistoryCache.DEFAULT) _recentShared.value = page.items
                _feed.value = SheetFeed(page.items, hasMore = page.hasMore, nextOffset = page.nextOffset, total = page.total)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == feedGen) _feed.update { it.copy(loading = false, error = e.message ?: "Couldn't reach the sheet") }
            }
        }
    }

    /** Next 25. Called when the list is scrolled near its end. */
    fun loadMore() {
        val s = _feed.value
        if (s.loading || s.loadingMore || !s.hasMore || !settings.isConnected) return
        val f = _filter.value
        val gen = feedGen
        _feed.update { it.copy(loadingMore = true, error = null) }
        feedJob = viewModelScope.launch {
            try {
                val (page, _) = api().fetchHistory(f.account, f.type, f.query, s.nextOffset)
                if (gen != feedGen) return@launch
                _feed.update { cur ->
                    val seen = cur.items.mapTo(HashSet()) { it.txn.txnKey }
                    cur.copy(
                        items = cur.items + page.items.filter { it.txn.txnKey !in seen },
                        nextOffset = page.nextOffset, hasMore = page.hasMore, total = page.total, loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == feedGen) _feed.update { it.copy(loadingMore = false, error = e.message ?: "Couldn't load more") }
            }
        }
    }

    /** Opens Activity already filtered to one account (from a Home account card). */
    fun showAccountHistory(nick: String?) {
        setHistorySource(HistorySource.SHEET)
        setFilter(HistoryFilter(account = nick))
    }

    private val _update = MutableStateFlow<AppUpdate?>(null)
    val update: StateFlow<AppUpdate?> = _update.asStateFlow()
    fun dismissUpdate() { _update.value = null }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            HistoryCache.read(getApplication(), HistoryCache.DEFAULT)?.let { _recentShared.value = it.items }
        }
        // A finished sync saved a fresh first page: show it everywhere without another request.
        viewModelScope.launch {
            syncing.drop(1).filter { !it }.collect {
                val page = withContext(Dispatchers.IO) { HistoryCache.read(getApplication(), HistoryCache.DEFAULT) }
                page?.let { _recentShared.value = it.items }
                val cur = _feed.value
                if (cur.nextOffset <= PAGE && !cur.loadingMore) {
                    if (_filter.value == HistoryFilter() && page != null) {
                        feedJob?.cancel(); feedGen++
                        _feed.value = SheetFeed(page.items, hasMore = page.hasMore, nextOffset = page.nextOffset, total = page.total)
                    } else reloadFeed(keepItems = true)
                }
            }
        }
        reloadFeed()
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
        reloadFeed()
    }

    suspend fun refreshAccounts(): Result<Int> = runCatching {
        val list = api().fetchAccounts()
        settings.accountsJson = BankAccount.listToJson(list)
        settings.accountsFetchedAt = System.currentTimeMillis()
        runCatching { SyncWorker.refreshHome(getApplication(), api()) }
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
        const val PAGE = SheetApi.PAGE_SIZE
        private const val RECENT = 6

        private fun parseSummary(json: String?) = Summary.parse(json)?.takeIf { it.accounts.isNotEmpty() }

        /**
         * Sheet summary + the entries it doesn't have yet, so balances and totals move the moment an SMS is captured,
         * even with no internet. Same rule as the sheet: a balance printed in the SMS wins; otherwise the last known
         * balance moves by the amount; an entry older than the known balance never overrides it.
         */
        fun withLocal(s: Summary, extra: List<TxnEntity>): Summary {
            if (extra.isEmpty()) return s
            val m = startOfMonth(); val y = startOfYear()
            val by = extra.groupBy { it.accountNick }
            fun add(p: PeriodStats, list: List<TxnEntity>) = list.fold(p) { acc, t ->
                acc + if (t.isCredit) PeriodStats(t.amount, 0.0, 1) else PeriodStats(0.0, t.amount, 1)
            }
            return s.copy(accounts = s.accounts.map { a ->
                val items = by[a.nickname] ?: return@map a
                var bal = a.balance
                var at = a.balanceAsOf
                for (t in items.sortedBy { it.timestamp }) {
                    val known = at
                    if (known != null && t.timestamp < known) continue
                    val printed = t.balance
                    val cur = bal
                    if (printed != null) { bal = printed; at = t.timestamp }
                    else if (cur != null && known != null) { bal = cur + if (t.isCredit) t.amount else -t.amount; at = t.timestamp }
                }
                a.copy(
                    balance = bal, balanceAsOf = at,
                    month = add(a.month, items.filter { it.timestamp >= m }),
                    year = add(a.year, items.filter { it.timestamp >= y }),
                    all = add(a.all, items),
                )
            })
        }

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
