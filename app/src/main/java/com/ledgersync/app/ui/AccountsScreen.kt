package com.ledgersync.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ledgersync.app.data.BankAccount
import kotlinx.coroutines.launch

@Composable
fun AccountsScreen(vm: MainViewModel) {
    val accounts by vm.accounts.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    fun refresh() {
        loading = true
        scope.launch {
            vm.refreshAccounts()
                .onSuccess { vm.toast("$it accounts loaded from the sheet") }
                .onFailure { vm.toast(it.message ?: "Couldn't reach the sheet") }
            loading = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 110.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Accounts", style = MaterialTheme.typography.headlineMedium, color = LS.Text)
                        Text("Only these accounts are captured", style = MaterialTheme.typography.bodyMedium, color = LS.Muted)
                    }
                    if (loading) CircularProgressIndicator(Modifier.padding(12.dp).width(22.dp).height(22.dp), color = LS.PrimarySoft, strokeWidth = 2.dp)
                    else IconButton(::refresh) { Icon(Icons.Rounded.Refresh, "Refresh", tint = LS.PrimarySoft) }
                }
                Spacer(Modifier.height(16.dp))
                GlassCard(padding = 16.dp) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.TableChart, null, tint = LS.Cyan)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "The list lives in your Google Sheet (“Accounts” tab) and is shared by every phone. " +
                                "Messages for any other account — including personal numbers on this phone — are ignored.",
                            color = LS.Muted, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            if (accounts.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.AccountBalance, "No accounts tracked",
                        "Add your company & personal bank accounts so LedgerSync knows which SMS to capture.",
                        "Load from sheet", ::refresh,
                    )
                }
            }
            val groups = accounts.groupBy { if (it.owner.equals("Personal", true)) "Personal" else "Company" }
            listOf("Company", "Personal").forEach { g ->
                val list = groups[g].orEmpty()
                if (list.isNotEmpty()) {
                    item { SectionHeader("$g · ${list.size}") }
                    items(list, key = { "a-" + it.nickname }) { a ->
                        AccountTile(a)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { if (vm.settings.isConnected) adding = true else vm.toast("Connect the sheet in Settings first") },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text("Add account") },
            containerColor = LS.Primary, contentColor = Color.White,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        )
    }
    if (adding) AddAccountSheet(vm) { adding = false }
}

@Composable
private fun AccountTile(a: BankAccount) {
    GlassCard(padding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BankAvatar(a.bank.ifBlank { a.nickname }, 48.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.nickname, style = MaterialTheme.typography.titleSmall, color = LS.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${a.bank} · A/c ${a.masked}", style = MaterialTheme.typography.bodySmall, color = LS.Muted)
                if (a.aliases.isNotEmpty()) Text("Cards: " + a.aliases.joinToString { "••$it" }, style = MaterialTheme.typography.bodySmall, color = LS.Faint)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(a.balance?.let { formatInrShort(it) } ?: "—", style = MaterialTheme.typography.titleSmall, color = LS.Text)
                Spacer(Modifier.height(4.dp))
                if (!a.active) Pill("Paused", LS.Faint) else Pill("Tracking", LS.Credit)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAccountSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf("") }
    var bank by remember { mutableStateOf("") }
    var number by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = LS.Surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 22.dp).navigationBarsPadding().imePadding()
        ) {
            Text("Add bank account", style = MaterialTheme.typography.headlineSmall, color = LS.Text)
            Text("It gets its own tab in the sheet and is tracked on every phone.", style = MaterialTheme.typography.bodyMedium, color = LS.Muted)
            Spacer(Modifier.height(18.dp))
            ChoiceChips(listOf("Company", "Personal"), owner, { owner = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            LsTextField(nickname, { nickname = it }, "Short name (e.g. HDFC Current)", leading = Icons.Rounded.Badge)
            Spacer(Modifier.height(10.dp))
            LsTextField(bank, { bank = it }, "Bank (e.g. HDFC Bank)", leading = Icons.Rounded.AccountBalance)
            Spacer(Modifier.height(10.dp))
            LsTextField(
                number, { number = it.filter { c -> c.isDigit() } }, "Account number", keyboardType = KeyboardType.Number,
                leading = Icons.Rounded.Numbers, supporting = "Full number, or at least the last 4 digits",
            )
            Spacer(Modifier.height(4.dp))
            LsTextField(
                aliases, { aliases = it }, "Linked debit-card last 4 digits (optional)", keyboardType = KeyboardType.Number,
                leading = Icons.Rounded.CreditCard, supporting = "Comma-separated — card spends are filed under this account",
            )
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = LS.Debit, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(18.dp))
            PrimaryButton("Save to sheet", loading = saving, enabled = nickname.isNotBlank() && number.length >= 4, onClick = {
                saving = true; error = null
                scope.launch {
                    vm.addAccount(
                        BankAccount(
                            nickname = nickname.trim(), bank = bank.trim(), number = number.trim(),
                            owner = if (owner == 0) "Company" else "Personal",
                            aliases = aliases.split(',', ' ').map { it.trim() }.filter { it.length >= 3 },
                        )
                    ).onSuccess { vm.toast("Account added"); onDismiss() }.onFailure { error = it.message }
                    saving = false
                }
            })
        }
    }
}
