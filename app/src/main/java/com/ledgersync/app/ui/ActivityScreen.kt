package com.ledgersync.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgersync.app.data.SyncState
import com.ledgersync.app.data.TxnEntity

private val typeFilters = listOf("All", "Money in", "Money out", "Not synced")
private fun typeOf(i: Int) = when (i) { 1 -> "CREDIT"; 2 -> "DEBIT"; else -> null }

/** One line in the list: the entry plus which phone captured it (null = this phone). */
private data class Entry(val txn: TxnEntity, val device: String?)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(vm: MainViewModel) {
    val source by vm.historySource.collectAsState()
    val filter by vm.historyFilter.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val feed by vm.feed.collectAsState()
    val queued by vm.queued.collectAsState()
    val localItems by vm.localItems.collectAsState()
    val localTotals by vm.localTotals.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var detail by remember { mutableStateOf<Entry?>(null) }
    val sheet = source == HistorySource.SHEET

    // Text field keeps its own state so typing stays instant; the list query follows it.
    var query by rememberSaveable { mutableStateOf(filter.query) }
    LaunchedEffect(query) { if (query != vm.historyFilter.value.query) vm.setFilter(vm.historyFilter.value.copy(query = query)) }
    LaunchedEffect(filter.query) { if (filter.query != query) query = filter.query }

    val typeIdx = when { filter.unsynced -> 3; filter.type == "CREDIT" -> 1; filter.type == "DEBIT" -> 2; else -> 0 }

    val entries: List<Entry> = remember(sheet, feed.items, queued, localItems, filter) {
        if (sheet) {
            val waiting = queued.filter { filter.matches(it) }
            val keys = waiting.mapTo(HashSet()) { it.txnKey }
            waiting.map { Entry(it, null) } + feed.items.filter { it.txn.txnKey !in keys }.map { Entry(it.txn, it.device) }
        } else localItems.map { Entry(it, null) }
    }
    val groups = remember(entries) { entries.groupBy { dayLabel(it.txn.timestamp) } }
    val hasMore = if (sheet) feed.hasMore else localTotals.count > localItems.size
    val loadingMore = sheet && feed.loadingMore

    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, entries.size, source, hasMore) {
        if (nearEnd && hasMore) { if (sheet) vm.loadMore() else vm.loadMoreLocal() }
    }
    // A new filter or source starts at the top.
    LaunchedEffect(filter.copy(query = ""), source) { if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp)) {
            Text("Activity", style = MaterialTheme.typography.headlineMedium, color = LS.Text)
            val sub = if (sheet) {
                val inSum = entries.filter { it.txn.isCredit }.sumOf { it.txn.amount }
                val outSum = entries.filter { !it.txn.isCredit }.sumOf { it.txn.amount }
                val count = if (filter.type == null && filter.query.isBlank() && feed.total > 0) "${entries.size} of ${feed.total}" else "${entries.size}"
                "$count entries · all devices · +${formatInrShort(inSum)} / −${formatInrShort(outSum)}"
            } else "${localTotals.count} entries · this phone · +${formatInrShort(localTotals.credit)} / −${formatInrShort(localTotals.debit)}"
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = LS.Muted)
            Spacer(Modifier.height(12.dp))
            ChoiceChips(HistorySource.entries.map { it.label }, source.ordinal, { vm.setHistorySource(HistorySource.entries[it]) }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            LsTextField(
                query, { query = it }, "Search name, amount, ref, account",
                leading = Icons.Rounded.Search,
                trailing = if (query.isNotEmpty()) ({ IconButton({ query = "" }) { Icon(Icons.Rounded.Close, null, tint = LS.Muted) } }) else null,
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shown = if (sheet) typeFilters.size - 1 else typeFilters.size
            items(shown) { i ->
                FilterChipPill(typeFilters[i], typeIdx == i) {
                    vm.setFilter(filter.copy(type = typeOf(i), unsynced = i == 3))
                }
            }
        }
        if (accounts.size > 1) {
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChipPill("All accounts", filter.account == null) { vm.setFilter(filter.copy(account = null)) } }
                items(accounts, key = { it.nickname }) { a ->
                    FilterChipPill(a.nickname, filter.account == a.nickname) {
                        vm.setFilter(filter.copy(account = if (filter.account == a.nickname) null else a.nickname))
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (sheet && feed.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = LS.Primary, trackColor = LS.Bg)
        }
        if (sheet && feed.error != null && entries.isNotEmpty()) {
            OfflineBanner(if (feed.cached) "Offline · showing the last saved copy" else feed.error!!) { vm.reloadFeed(keepItems = true) }
        }

        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = { if (sheet) { vm.syncNow(true); vm.reloadFeed(keepItems = true) } else vm.syncNow(true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                entries.isEmpty() && sheet && feed.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    CircularProgressIndicator(Modifier.padding(top = 48.dp).size(28.dp), color = LS.Primary, strokeWidth = 3.dp)
                }
                entries.isEmpty() && sheet && feed.error != null -> LazyColumn(Modifier.fillMaxSize()) {
                    item { EmptyState(Icons.Rounded.CloudOff, "Couldn't load the sheet", feed.error!!, "Try again") { vm.reloadFeed() } }
                }
                entries.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        val blankFilter = filter == com.ledgersync.app.ui.HistoryFilter()
                        EmptyState(
                            Icons.Rounded.Receipt,
                            if (blankFilter) "Nothing captured yet" else "No matches",
                            if (blankFilter) "Import past SMS from Settings, or wait for the next bank message." else "Try another search or filter.",
                        )
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(), state = listState,
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp),
                ) {
                    groups.forEach { (day, items) ->
                        stickyHeader(key = "h-$day") {
                            Row(
                                Modifier.fillMaxWidth().background(LS.Bg).padding(horizontal = 6.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(day, color = LS.Text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                val dIn = items.filter { it.txn.isCredit }.sumOf { it.txn.amount }
                                val dOut = items.filter { !it.txn.isCredit }.sumOf { it.txn.amount }
                                if (dIn > 0) Text("+" + formatInrShort(dIn), color = LS.Credit, style = MaterialTheme.typography.labelMedium)
                                if (dIn > 0 && dOut > 0) Spacer(Modifier.width(10.dp))
                                if (dOut > 0) Text("−" + formatInrShort(dOut), color = LS.Debit, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        items(items, key = { it.txn.txnKey }) { e -> TxnRow(e.txn) { detail = e } }
                    }
                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                            when {
                                loadingMore -> CircularProgressIndicator(Modifier.size(22.dp), color = LS.Primary, strokeWidth = 2.dp)
                                hasMore -> Text(
                                    "Load more", color = LS.PrimarySoft, style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { if (sheet) vm.loadMore() else vm.loadMoreLocal() }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                else -> Text("That's everything · ${entries.size} entries", color = LS.Faint, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    detail?.let { e -> TxnDetailSheet(e.txn, e.device) { detail = null } }
}

@Composable
private fun OfflineBanner(text: String, onRetry: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(LS.Amber.copy(alpha = 0.12f)).clickable(onClick = onRetry).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = LS.Amber, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = LS.Text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2)
        Text("Retry", color = LS.Amber, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxnDetailSheet(t: TxnEntity, device: String? = null, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LS.Surface,
        dragHandle = null,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(22.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TxnIcon(t.isCredit, 60.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                (if (t.isCredit) "+" else "−") + formatInr(t.amount),
                fontSize = 32.sp, fontWeight = FontWeight.Bold, color = if (t.isCredit) LS.Credit else LS.Text,
            )
            Text(t.party.ifBlank { if (t.isCredit) "Money received" else "Payment" }, color = LS.Muted, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            val (label, color) = when (t.syncState) {
                SyncState.SYNCED -> "Saved in Google Sheet" to LS.Credit
                SyncState.DUPLICATE -> "Already in sheet (another device)" to LS.Credit
                SyncState.FAILED -> "Waiting to retry · ${t.lastError ?: ""}" to LS.Amber
                else -> "Queued — uploads when online" to LS.Amber
            }
            Pill(label, color)
            Spacer(Modifier.height(18.dp))
            GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                KeyValue("Account", "${t.accountNick} · ${t.bank} ${t.accountMasked}")
                KeyValue("Owner", t.owner)
                KeyValue("Type", if (t.isCredit) "Credit" else "Debit", if (t.isCredit) LS.Credit else LS.Debit)
                KeyValue("Mode", t.mode)
                KeyValue("Balance after", t.balance?.let { formatInr(it) } ?: "")
                KeyValue("Reference", t.reference)
                KeyValue("Date", dateTimeOf(t.timestamp))
                KeyValue("Sender", t.sender)
                KeyValue("Captured by", device ?: "This phone")
                if (device == null) KeyValue("Synced", t.syncedAt?.let { relativeTime(it) } ?: "")
            }
            Spacer(Modifier.height(12.dp))
            GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Original SMS", color = LS.Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton({ copy(ctx, t.body) }) { Icon(Icons.Rounded.ContentCopy, "Copy", tint = LS.Muted) }
                }
                Text(
                    t.body, color = LS.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(LS.Bg).padding(12.dp).fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("sms", text))
}
