package com.ledgersync.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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

private val filters = listOf("All", "Money in", "Money out", "Not synced")

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ActivityScreen(vm: MainViewModel) {
    val txns by vm.txns.collectAsState()
    val accounts by vm.accounts.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) }
    var account by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<TxnEntity?>(null) }

    val shown = txns.filter { t ->
        (filter == 0 || (filter == 1 && t.isCredit) || (filter == 2 && !t.isCredit) ||
            (filter == 3 && (t.syncState == SyncState.PENDING || t.syncState == SyncState.FAILED))) &&
            (account == null || t.accountNick == account) &&
            (query.isBlank() || listOf(t.party, t.accountNick, t.mode, t.reference, t.body, t.amount.toString())
                .any { it.contains(query.trim(), ignoreCase = true) })
    }
    val groups = shown.groupBy { dayLabel(it.timestamp) }
    val inSum = shown.filter { it.isCredit }.sumOf { it.amount }
    val outSum = shown.filter { !it.isCredit }.sumOf { it.amount }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp)) {
            Text("Activity", style = MaterialTheme.typography.headlineMedium, color = LS.Text)
            Text(
                "${shown.size} entries · +${formatInrShort(inSum)} / −${formatInrShort(outSum)}",
                style = MaterialTheme.typography.bodyMedium, color = LS.Muted,
            )
            Spacer(Modifier.height(14.dp))
            LsTextField(
                query, { query = it }, "Search name, amount, ref, account",
                leading = Icons.Rounded.Search,
                trailing = if (query.isNotEmpty()) ({ IconButton({ query = "" }) { Icon(Icons.Rounded.Close, null, tint = LS.Muted) } }) else null,
            )
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filters.size) { i -> FilterChipPill(filters[i], filter == i) { filter = i } }
        }
        if (accounts.size > 1) {
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChipPill("All accounts", account == null) { account = null } }
                items(accounts, key = { it.nickname }) { a -> FilterChipPill(a.nickname, account == a.nickname) { account = if (account == a.nickname) null else a.nickname } }
            }
        }
        Spacer(Modifier.height(6.dp))

        if (shown.isEmpty()) {
            EmptyState(
                Icons.Rounded.Receipt,
                if (txns.isEmpty()) "Nothing captured yet" else "No matches",
                if (txns.isEmpty()) "Import past SMS from Settings, or wait for the next bank message." else "Try another search or filter.",
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 24.dp)) {
                groups.forEach { (day, items) ->
                    stickyHeader(key = "h-$day") {
                        Row(
                            Modifier.fillMaxWidth().background(LS.Bg).padding(horizontal = 6.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(day, color = LS.Text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            val dIn = items.filter { it.isCredit }.sumOf { it.amount }
                            val dOut = items.filter { !it.isCredit }.sumOf { it.amount }
                            if (dIn > 0) Text("+" + formatInrShort(dIn), color = LS.Credit, style = MaterialTheme.typography.labelMedium)
                            if (dIn > 0 && dOut > 0) Spacer(Modifier.width(10.dp))
                            if (dOut > 0) Text("−" + formatInrShort(dOut), color = LS.Debit, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    items(items, key = { it.txnKey }) { t -> TxnRow(t) { detail = t } }
                }
            }
        }
    }
    detail?.let { TxnDetailSheet(it) { detail = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxnDetailSheet(t: TxnEntity, onDismiss: () -> Unit) {
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
                else -> "Queued for upload" to LS.Amber
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
                KeyValue("Synced", t.syncedAt?.let { relativeTime(it) } ?: "")
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
