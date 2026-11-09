package com.ledgersync.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().background(LS.Bg).statusBarsPadding().navigationBarsPadding().imePadding()) {
        // progress dots
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.Center) {
            repeat(4) { i ->
                val c by animateColorAsState(if (i <= step) LS.Primary else LS.Surface3, label = "dot")
                Box(Modifier.padding(horizontal = 4.dp).height(6.dp).width(if (i == step) 28.dp else 10.dp).clip(RoundedCornerShape(50)).background(c))
            }
        }
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(380)) { it / 4 * dir } + fadeIn(tween(380))) togetherWith
                    (slideOutHorizontally(tween(260)) { -it / 4 * dir } + fadeOut(tween(200)))
            },
            modifier = Modifier.weight(1f),
            label = "onb",
        ) { s ->
            when (s) {
                0 -> WelcomeStep { step = 1 }
                1 -> PermissionStep { step = 2 }
                2 -> ConnectStep(vm, onBack = { step = 1 }) { step = 3 }
                else -> ImportStep(vm)
            }
        }
    }
}

@Composable
private fun StepScaffold(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        content = content,
    )
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    val t = rememberInfiniteTransition(label = "logo")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000)), label = "rot")
    val glow by t.animateFloat(0.25f, 0.55f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "glow")
    StepScaffold {
        Spacer(Modifier.height(30.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(210.dp).rotate(rot).clip(CircleShape).background(LS.Hero).padding(2.dp).clip(CircleShape).background(LS.Bg.copy(alpha = 0.9f)))
            Box(Modifier.size(150.dp).clip(CircleShape).background(LS.Primary.copy(alpha = glow)))
            Box(Modifier.size(104.dp).clip(RoundedCornerShape(32.dp)).background(LS.Hero), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.TableChart, null, tint = Color.White, modifier = Modifier.size(52.dp))
            }
        }
        Spacer(Modifier.height(34.dp))
        Text("LedgerSync", style = MaterialTheme.typography.displaySmall, color = LS.Text, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Every bank debit & credit SMS — straight into your company Google Sheet. Automatically.",
            style = MaterialTheme.typography.bodyLarge, color = LS.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(30.dp))
        Feature(Icons.Rounded.Bolt, "Instant & always on", "Captures in the background, even when the app is closed.")
        Feature(Icons.Rounded.AccountBalance, "Only your accounts", "Tracks just the company & personal accounts you choose.")
        Feature(Icons.Rounded.TableChart, "Perfect sheet", "Master sheet, one tab per account, live monthly & yearly totals.")
        Feature(Icons.Rounded.Verified, "Zero duplicates", "Install on many phones — each transaction is stored exactly once.")
        Spacer(Modifier.height(28.dp))
        PrimaryButton("Get started", onNext, icon = Icons.Rounded.ArrowForward)
    }
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(LS.Primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LS.PrimarySoft)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = LS.Text, style = MaterialTheme.typography.titleSmall)
            Text(body, color = LS.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionStep(onNext: () -> Unit) {
    val ctx = LocalContext.current
    var sms by remember { mutableStateOf(granted(ctx, Manifest.permission.RECEIVE_SMS) && granted(ctx, Manifest.permission.READ_SMS)) }
    var notif by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) }
    var battery by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        sms = granted(ctx, Manifest.permission.RECEIVE_SMS) && granted(ctx, Manifest.permission.READ_SMS)
        notif = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
        battery = isIgnoringBattery(ctx)
    }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        sms = r.values.all { it } || (granted(ctx, Manifest.permission.RECEIVE_SMS) && granted(ctx, Manifest.permission.READ_SMS))
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = it }

    StepScaffold {
        Spacer(Modifier.height(16.dp))
        Text("A few permissions", style = MaterialTheme.typography.headlineMedium, color = LS.Text)
        Spacer(Modifier.height(6.dp))
        Text("LedgerSync reads only bank transaction messages for your tracked accounts. Personal chats are never read or uploaded.", color = LS.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        PermCard(Icons.Rounded.Sms, "SMS access", "Required — to receive and read bank messages", sms, required = true) {
            smsLauncher.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
        }
        PermCard(Icons.Rounded.Notifications, "Notifications", "Get a quick alert each time an entry is saved", notif) {
            if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        PermCard(Icons.Rounded.BatteryChargingFull, "Run in background", "Stops Android from pausing uploads", battery) { requestBattery(ctx) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(LS.Surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = LS.Cyan, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Data goes only to your own Google Sheet. No third-party servers.", color = LS.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(if (sms) "Continue" else "Allow SMS access to continue", onNext, enabled = sms, icon = Icons.Rounded.ArrowForward)
    }
}

@Composable
private fun PermCard(icon: ImageVector, title: String, body: String, ok: Boolean, required: Boolean = false, onClick: () -> Unit) {
    val border by animateColorAsState(if (ok) LS.Credit.copy(alpha = 0.5f) else LS.Stroke, label = "b")
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(20.dp)).background(LS.Surface)
            .border(1.dp, border, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background((if (ok) LS.Credit else LS.Primary).copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (ok) LS.Credit else LS.PrimarySoft)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = LS.Text, style = MaterialTheme.typography.titleSmall)
                if (required) { Spacer(Modifier.width(6.dp)); Pill("Required", LS.Amber) }
            }
            Text(body, color = LS.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        if (ok) Icon(Icons.Rounded.CheckCircle, null, tint = LS.Credit)
        else TextButton(onClick) { Text("Allow", color = LS.PrimarySoft) }
    }
}

@Composable
private fun ConnectStep(vm: MainViewModel, onBack: () -> Unit, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(vm.settings.scriptUrl) }
    var key by remember { mutableStateOf(vm.settings.secretKey) }
    var device by remember { mutableStateOf(vm.settings.deviceName) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    StepScaffold {
        Spacer(Modifier.height(16.dp))
        Text("Connect Google Sheet", style = MaterialTheme.typography.headlineMedium, color = LS.Text)
        Spacer(Modifier.height(6.dp))
        Text("In the sheet open  LedgerSync ▸ Connection details  and copy the two values below.", color = LS.Muted, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(22.dp))
        LsTextField(url, { url = it.trim() }, "Web App URL", leading = Icons.Rounded.Link, supporting = "https://script.google.com/macros/s/…/exec")
        Spacer(Modifier.height(6.dp))
        LsTextField(key, { key = it.trim() }, "Secret key", leading = Icons.Rounded.Key)
        Spacer(Modifier.height(12.dp))
        LsTextField(device, { device = it }, "Name for this phone", leading = Icons.Rounded.PhoneAndroid, supporting = "Shown in the sheet's “Device” column — e.g. Accounts Manager")
        msg?.let { (ok, m) ->
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.Lock, null, tint = if (ok) LS.Credit else LS.Debit, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(m, color = if (ok) LS.Credit else LS.Debit, style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Connect", loading = busy, enabled = url.isNotBlank() && key.isNotBlank(), icon = Icons.Rounded.Link, onClick = {
            busy = true; msg = null
            scope.launch {
                vm.testConnection(url, key).onSuccess {
                    vm.saveConnection(url, key, device)
                    val n = vm.refreshAccounts().getOrDefault(0)
                    msg = true to "$it"
                    busy = false
                    if (n >= 0) onNext()
                }.onFailure {
                    msg = false to (it.message ?: "Connection failed")
                    busy = false
                }
            }
        })
        Spacer(Modifier.height(8.dp))
        TextButton(onBack, Modifier.align(Alignment.CenterHorizontally)) { Text("Back", color = LS.Muted) }
    }
}

@Composable
private fun ImportStep(vm: MainViewModel) {
    val accounts by vm.accounts.collectAsState()
    val imp by vm.importState.collectAsState()
    var choice by remember { mutableIntStateOf(0) }
    val running = imp?.running == true
    val done = imp != null && !running

    StepScaffold {
        Spacer(Modifier.height(16.dp))
        Text("You're connected 🎉", style = MaterialTheme.typography.headlineMedium, color = LS.Text)
        Spacer(Modifier.height(6.dp))
        Text(
            if (accounts.isEmpty()) "No accounts in the sheet yet — add them from the Accounts tab after setup."
            else "Tracking ${accounts.size} account${if (accounts.size == 1) "" else "s"}:",
            color = LS.Muted, style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        accounts.take(8).forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                BankAvatar(a.bank.ifBlank { a.nickname }, 36.dp)
                Spacer(Modifier.width(12.dp))
                Text(a.nickname, color = LS.Text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(a.masked, color = LS.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text("Import older bank SMS?", color = LS.Text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            ChoiceChips(listOf("Skip", "This month", "This year"), choice, { if (!running && !done) choice = it }, Modifier.fillMaxWidth())
            imp?.let { st ->
                Spacer(Modifier.height(14.dp))
                if (st.running) {
                    LinearProgressIndicator(
                        progress = { if (st.total > 0) st.scanned / st.total.toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = LS.Primary, trackColor = LS.Surface3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Reading ${st.scanned} of ${st.total} messages…", color = LS.Muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Found ${st.matched} bank entries · ${st.added} queued for the sheet", color = LS.Credit, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        when {
            running -> PrimaryButton("Importing…", {}, loading = true)
            done || choice == 0 || accounts.isEmpty() -> PrimaryButton("Open dashboard", { vm.finishOnboarding() }, icon = Icons.Rounded.ArrowForward)
            else -> PrimaryButton("Import & continue", {
                vm.importHistory(if (choice == 1) MainViewModel.startOfMonth() else MainViewModel.startOfYear())
            }, icon = Icons.Rounded.ArrowForward)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Tip: install LedgerSync on every phone that receives bank SMS. The sheet keeps each transaction once.",
            color = LS.Faint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            fontSize = 12.sp,
        )
    }
}
