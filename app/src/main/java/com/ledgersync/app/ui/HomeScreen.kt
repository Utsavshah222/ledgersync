package com.ledgersync.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgersync.app.data.AccountSummary
import com.ledgersync.app.data.Period
import com.ledgersync.app.data.PeriodStats
import com.ledgersync.app.data.TxnEntity

private val owners = listOf("All", "Company", "Personal")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, onOpenActivity: () -> Unit, onOpenAccounts: () -> Unit, onOpenSettings: () -> Unit) {
    val dash by vm.dashboard.collectAsState()
    val recentLocal by vm.recentLocal.collectAsState()
    val recentShared by vm.recentShared.collectAsState()
    val queued by vm.queued.collectAsState()
    val pending by vm.pending.collectAsState()
    val failed by vm.failed.collectAsState()
    val syncing by vm.syncing.collectAsState()
    vm.settings.version.collectAsState().value
    var ownerIdx by rememberSaveable { mutableIntStateOf(0) }
    var periodIdx by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Pair<TxnEntity, String?>?>(null) }
    val update by vm.update.collectAsState()
    val ctx = LocalContext.current

    val period = Period.entries[periodIdx]
    val all = dash?.summary?.accounts.orEmpty()
    val shown = if (ownerIdx == 0) all else all.filter { it.owner.equals(owners[ownerIdx], true) }
    val totals = shown.fold(PeriodStats()) { a, s -> a + s.period(period) }
    val totalBalance = shown.sumOf { it.balance ?: 0.0 }
    // Newest from every phone (queued entries on this phone first); falls back to this phone before the first sync.
    val recent: List<Pair<TxnEntity, String?>> = remember(recentShared, queued, recentLocal) {
        if (recentShared.isEmpty() && queued.isEmpty()) recentLocal.map { it to null }
        else {
            val keys = queued.mapTo(HashSet()) { it.txnKey }
            (queued.map { it to null } + recentShared.filter { it.txn.txnKey !in keys }.map { it.txn to it.device })
                .sortedByDescending { it.first.timestamp }.take(6)
        }
    }
    val recentFromAll = recentShared.isNotEmpty()

    PullToRefreshBox(isRefreshing = syncing, onRefresh = { vm.syncNow(true) }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), color = LS.Muted, style = MaterialTheme.typography.bodyMedium)
                        Text("LedgerSync", style = MaterialTheme.typography.headlineMedium, color = LS.Text)
                    }
                    SyncPill(pending, failed, syncing, vm.settings.isConnected) { if (vm.settings.isConnected) vm.syncNow(true) else onOpenSettings() }
                }
            }

            update?.let { u ->
                item {
                    UpdateBanner(u.version, onUpdate = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.apkUrl)))
                    }, onDismiss = vm::dismissUpdate)
                    Spacer(Modifier.height(14.dp))
                }
            }

            item {
                HeroCard(
                    balance = totalBalance, count = shown.size, fromSheet = dash?.fromSheet == true,
                    updated = dash?.summary?.fetchedAt ?: 0L, ownerIdx = ownerIdx, onOwner = { ownerIdx = it },
                )
            }

            item {
                Spacer(Modifier.height(18.dp))
                ChoiceChips(Period.entries.map { it.label }, periodIdx, { periodIdx = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatTile("Money in", totals.credit, LS.Credit, true, Modifier.weight(1f))
                    StatTile("Money out", totals.debit, LS.Debit, false, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                NetCard(totals)
            }

            item { SectionHeader("Accounts", "Manage", onOpenAccounts) }
            if (shown.isEmpty()) {
                item {
                    GlassCard {
                        EmptyState(
                            Icons.Rounded.AccountBalance, "No accounts yet",
                            "Add the company's bank accounts in the Accounts tab or in the sheet's “Accounts” tab.",
                        )
                    }
                }
            }
            items(shown, key = { "acc-" + it.nickname }) { a ->
                AccountCard(a, period) { vm.showAccountHistory(a.nickname); onOpenActivity() }
                Spacer(Modifier.height(10.dp))
            }

            item {
                SectionHeader(if (recentFromAll) "Recent · all devices" else "Captured on this phone", if (recent.isNotEmpty()) "See all" else null) {
                    vm.showAccountHistory(null); onOpenActivity()
                }
            }
            if (recent.isEmpty()) {
                item {
                    GlassCard {
                        EmptyState(
                            if (vm.settings.isConnected) Icons.Rounded.Inbox else Icons.Rounded.CloudOff,
                            "Waiting for bank SMS",
                            "New debit & credit messages for tracked accounts appear here instantly and go straight to the sheet.",
                        )
                    }
                }
            } else {
                item {
                    GlassCard(padding = 8.dp) {
                        recent.forEach { e -> TxnRow(e.first) { detail = e } }
                    }
                }
            }
        }
    }
    detail?.let { (t, device) -> TxnDetailSheet(t, device) { detail = null } }
}

@Composable
private fun HeroCard(balance: Double, count: Int, fromSheet: Boolean, updated: Long, ownerIdx: Int, onOwner: (Int) -> Unit) {
    val shown = animatedDouble(balance)
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(LS.Hero)
    ) {
        // soft decorative orbs
        Box(Modifier.size(180.dp).offset(x = 200.dp, y = (-60).dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)))
        Box(Modifier.size(120.dp).offset(x = 250.dp, y = 90.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.06f)))
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Total balance", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(
                    if (fromSheet) "All devices" else "This phone",
                    color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f)).padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(formatInr(shown), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                "$count account${if (count == 1) "" else "s"} · updated ${relativeTime(updated)}",
                color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.18f)).padding(3.dp)
            ) {
                owners.forEachIndexed { i, o ->
                    val active = i == ownerIdx
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                            .background(if (active) Color.White else Color.Transparent)
                            .clickable { onOwner(i) }.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(o, color = if (active) Color(0xFF2B2A6B) else Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Double, color: Color, credit: Boolean, modifier: Modifier) {
    val v = animatedDouble(value)
    GlassCard(modifier, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(if (credit) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast, null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(label, color = LS.Muted, style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(12.dp))
        Text(formatInrShort(v), color = LS.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(formatInr(value), color = LS.Faint, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun NetCard(t: PeriodStats) {
    val total = t.credit + t.debit
    val ratio by animateFloatAsState(if (total > 0) (t.credit / total).toFloat() else 0.5f, tween(900), label = "ratio")
    GlassCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Net flow", color = LS.Muted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                (if (t.net >= 0) "+" else "−") + formatInr(kotlin.math.abs(t.net)),
                color = if (t.net >= 0) LS.Credit else LS.Debit, style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50))) {
            if (total <= 0) Box(Modifier.fillMaxSize().background(LS.Surface3))
            else {
                Box(Modifier.weight(ratio.coerceIn(0.02f, 0.98f)).fillMaxHeight().background(LS.Credit))
                Spacer(Modifier.width(3.dp))
                Box(Modifier.weight((1 - ratio).coerceIn(0.02f, 0.98f)).fillMaxHeight().background(LS.Debit))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("${t.count} transactions", color = LS.Faint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AccountCard(a: AccountSummary, period: Period, onHistory: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val p = a.period(period)
    GlassCard(onClick = { expanded = !expanded }, padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankAvatar(a.bank.ifBlank { a.nickname })
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.nickname, style = MaterialTheme.typography.titleSmall, color = LS.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Pill(a.owner, if (a.owner.equals("Personal", true)) LS.Amber else LS.Cyan)
                }
                Text("${a.bank} ${a.masked}", style = MaterialTheme.typography.bodySmall, color = LS.Muted, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(a.balance?.let { formatInrShort(it) } ?: "—", style = MaterialTheme.typography.titleMedium, color = LS.Text)
                Text("balance", style = MaterialTheme.typography.labelSmall, color = LS.Faint)
            }
        }
        AnimatedVisibility(expanded) {
            Column {
                Spacer(Modifier.height(14.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(LS.Stroke))
                Spacer(Modifier.height(12.dp))
                Row {
                    MiniStat("In · ${period.label}", formatInr(p.credit), LS.Credit, Modifier.weight(1f))
                    MiniStat("Out", formatInr(p.debit), LS.Debit, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row {
                    MiniStat("Net", (if (p.net >= 0) "+" else "−") + formatInr(kotlin.math.abs(p.net)), if (p.net >= 0) LS.Credit else LS.Debit, Modifier.weight(1f))
                    MiniStat("Balance as of", a.balanceAsOf?.let { relativeTime(it) } ?: "—", LS.Text, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "View transactions →", color = LS.PrimarySoft, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onHistory).padding(vertical = 6.dp),
                )
            }
        }
        if (!expanded) {
            Spacer(Modifier.height(10.dp))
            Row {
                Text("+" + formatInrShort(p.credit), color = LS.Credit, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(12.dp))
                Text("−" + formatInrShort(p.debit), color = LS.Debit, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text("${p.count} txns", color = LS.Faint, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, color = LS.Faint, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun UpdateBanner(version: String, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(LS.Cyan.copy(alpha = 0.12f))
            .border(1.dp, LS.Cyan.copy(alpha = 0.4f), RoundedCornerShape(20.dp)).clickable(onClick = onUpdate).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.SystemUpdate, null, tint = LS.Cyan)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Update available · v$version", color = LS.Text, style = MaterialTheme.typography.titleSmall)
            Text("Tap to download, then open the file to install", color = LS.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Rounded.Close, "Dismiss", tint = LS.Muted, modifier = Modifier.clip(CircleShape).clickable(onClick = onDismiss).padding(4.dp))
    }
}
