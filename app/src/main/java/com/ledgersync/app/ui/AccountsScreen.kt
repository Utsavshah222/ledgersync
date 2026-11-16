package com.ledgersync.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ledgersync.app.data.AccountSummary

/**
 * Admin edition. A read-only list of every account LedgerSync has discovered from the bank SMS it captured —
 * grouped automatically by bank + last 4 digits. There is nothing to add by hand any more.
 */
@Composable
fun AccountsScreen(vm: MainViewModel) {
    val dash by vm.dashboard.collectAsState()
    val syncing by vm.syncing.collectAsState()
    val accounts = dash?.summary?.accounts.orEmpty().sortedByDescending { it.balance ?: Double.NEGATIVE_INFINITY }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Accounts", style = MaterialTheme.typography.headlineMedium, color = LS.Text)
                        Text("Detected automatically from bank SMS", style = MaterialTheme.typography.bodyMedium, color = LS.Muted)
                    }
                    if (syncing) CircularProgressIndicator(Modifier.padding(12.dp).width(22.dp).height(22.dp), color = LS.PrimarySoft, strokeWidth = 2.dp)
                    else IconButton({ vm.syncNow(true) }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = LS.PrimarySoft) }
                }
                Spacer(Modifier.height(16.dp))
                GlassCard(padding = 16.dp) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.TableChart, null, tint = LS.Cyan)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "No setup needed. Every bank account that sends a debit or credit SMS to any connected phone " +
                                "shows up here on its own, with its latest balance. Use the sheet's “Filter” tab to slice and export the data.",
                            color = LS.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (accounts.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.AccountBalance, "No accounts yet",
                        "As soon as a bank SMS is captured on any connected phone, its account appears here automatically.",
                    )
                }
            }
            item { if (accounts.isNotEmpty()) SectionHeader("${accounts.size} account${if (accounts.size == 1) "" else "s"}") }
            items(accounts, key = { "a-" + it.nickname }) { a ->
                AccountTile(a)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun AccountTile(a: AccountSummary) {
    GlassCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankAvatar(a.bank.ifBlank { a.nickname }, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.nickname, style = MaterialTheme.typography.titleSmall, color = LS.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${a.bank} · A/c ${a.masked}", style = MaterialTheme.typography.bodySmall, color = LS.Muted)
                Spacer(Modifier.height(4.dp))
                Row {
                    Text("+" + formatInrShort(a.all.credit), color = LS.Credit, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(12.dp))
                    Text("−" + formatInrShort(a.all.debit), color = LS.Debit, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(12.dp))
                    Text("${a.all.count} txns", color = LS.Faint, style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(a.balance?.let { formatInrShort(it) } ?: "—", style = MaterialTheme.typography.titleSmall, color = LS.Text)
                Text("balance", style = MaterialTheme.typography.labelSmall, color = LS.Faint)
            }
        }
    }
}
