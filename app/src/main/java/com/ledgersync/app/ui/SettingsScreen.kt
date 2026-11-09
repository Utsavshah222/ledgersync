package com.ledgersync.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = vm.settings
    vm.settings.version.collectAsState().value
    val pending by vm.pending.collectAsState()
    val failed by vm.failed.collectAsState()
    val imp by vm.importState.collectAsState()

    var url by remember { mutableStateOf(s.scriptUrl) }
    var key by remember { mutableStateOf(s.secretKey) }
    var device by remember { mutableStateOf(s.deviceName) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var labSender by remember { mutableStateOf("VM-HDFCBK") }
    var labBody by remember { mutableStateOf("") }
    var labOut by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val appVersion = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryOk = isIgnoringBattery(ctx) }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 28.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, color = LS.Text)
        Text("Device: ${s.deviceName}", style = MaterialTheme.typography.bodyMedium, color = LS.Muted)

        // ---------------- Connection ----------------
        SectionHeader("Google Sheet connection")
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (s.isConnected) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber, null, tint = if (s.isConnected) LS.Credit else LS.Amber)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (s.isConnected) "Connected" else "Not connected", color = LS.Text, style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (s.lastSyncError != null) "Last error: ${s.lastSyncError}" else "Last sync ${relativeTime(s.lastSyncAt)}",
                        color = if (s.lastSyncError != null) LS.Amber else LS.Muted, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            LsTextField(url, { url = it.trim() }, "Web App URL", leading = Icons.Rounded.Link, singleLine = true)
            Spacer(Modifier.height(10.dp))
            LsTextField(
                key, { key = it.trim() }, "Secret key", leading = Icons.Rounded.Key,
                visual = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = { IconButton({ showKey = !showKey }) { Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null, tint = LS.Muted) } },
            )
            Spacer(Modifier.height(10.dp))
            LsTextField(device, { device = it }, "This device's name (shown in the sheet)", leading = Icons.Rounded.PhoneAndroid)
            status?.let { (ok, msg) ->
                Spacer(Modifier.height(10.dp))
                Text(msg, color = if (ok) LS.Credit else LS.Debit, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("Test & save", loading = testing, icon = Icons.Rounded.Shield, onClick = {
                testing = true; status = null
                scope.launch {
                    vm.testConnection(url, key)
                        .onSuccess { status = true to it; vm.saveConnection(url, key, device); vm.syncNow(true) }
                        .onFailure { status = false to (it.message ?: "Failed") }
                    testing = false
                }
            })
        }

        // ---------------- Sync ----------------
        SectionHeader("Sync")
        GlassCard(padding = 6.dp) {
            ActionRow(Icons.Rounded.Sync, "Sync now", "$pending waiting · runs automatically every 15 min") { vm.syncNow(true) }
            if (failed > 0) ActionRow(Icons.Rounded.CloudUpload, "Retry $failed failed uploads", "They are safe on this phone") { vm.syncNow(true) }
            ToggleRow(Icons.Rounded.Notifications, "Alert on each capture", "Show a notification for every saved SMS", s.notifyOnCapture) { s.notifyOnCapture = it }
            ToggleRow(Icons.Rounded.Shield, "Bank senders only", "Ignore SMS from normal phone numbers (anti-spoofing)", s.bankSendersOnly) { s.bankSendersOnly = it }
        }

        // ---------------- Import ----------------
        SectionHeader("Import past SMS")
        GlassCard {
            Text(
                "Reads the inbox and adds older bank messages for tracked accounts. The sheet skips anything it already has, so it's safe to run again.",
                color = LS.Muted, style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            val running = imp?.running == true
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("This month", { vm.importHistory(MainViewModel.startOfMonth()) }, Modifier.weight(1f), enabled = !running)
                SecondaryButton("3 months", { vm.importHistory(MainViewModel.monthsAgo(2)) }, Modifier.weight(1f), enabled = !running)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("This year", { vm.importHistory(MainViewModel.startOfYear()) }, Modifier.weight(1f), enabled = !running)
                SecondaryButton("12 months", { vm.importHistory(MainViewModel.monthsAgo(11)) }, Modifier.weight(1f), enabled = !running)
            }
            imp?.let { st ->
                Spacer(Modifier.height(12.dp))
                if (st.running) {
                    LinearProgressIndicator(
                        progress = { if (st.total > 0) st.scanned / st.total.toFloat() else 0f },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = LS.Primary, trackColor = LS.Surface3,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Scanning ${st.scanned} / ${st.total} messages…", color = LS.Muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Done · scanned ${st.scanned} SMS · ${st.matched} bank entries · ${st.added} new", color = LS.Credit, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---------------- Reliability ----------------
        SectionHeader("Always-on capture")
        GlassCard(padding = 6.dp) {
            ActionRow(
                Icons.Rounded.BatteryChargingFull,
                if (batteryOk) "Battery: unrestricted ✓" else "Allow background running",
                if (batteryOk) "Android won't put LedgerSync to sleep" else "Recommended — stops Android from delaying uploads",
                tint = if (batteryOk) LS.Credit else LS.Amber,
            ) { requestBattery(ctx) }
            ActionRow(Icons.Rounded.RocketLaunch, "Autostart & app settings", "Xiaomi / Vivo / Oppo / Realme: enable Autostart") {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            }
        }

        // ---------------- Parser lab ----------------
        SectionHeader("Parser Lab")
        GlassCard {
            Text("Paste any bank SMS to see exactly how it will be read.", color = LS.Muted, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(10.dp))
            LsTextField(labSender, { labSender = it }, "Sender ID", leading = Icons.Rounded.Science)
            Spacer(Modifier.height(8.dp))
            LsTextField(labBody, { labBody = it }, "SMS text", singleLine = false, minLines = 3)
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Analyse", { labOut = vm.parseTest(labSender, labBody) }, Modifier.fillMaxWidth(), enabled = labBody.isNotBlank())
            if (labOut.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    labOut, color = LS.Text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(LS.Bg).padding(12.dp),
                )
            }
        }

        // ---------------- Danger ----------------
        SectionHeader("Data")
        GlassCard(padding = 6.dp) {
            ActionRow(Icons.Rounded.History, "Re-run setup", "Show the welcome & permission steps again") { s.onboarded = false }
            ActionRow(Icons.Rounded.DeleteOutline, "Clear history on this phone", "The Google Sheet is not affected", tint = LS.Debit) { confirmClear = true }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "LedgerSync $appVersion · data goes only to your own Google Sheet",
            color = LS.Faint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = LS.Surface2,
            title = { Text("Clear local history?") },
            text = { Text("Removes captured entries from this phone only. Unsynced entries ($pending) will be lost.") },
            confirmButton = { TextButton({ vm.clearLocal(); confirmClear = false }) { Text("Clear", color = LS.Debit) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ActionRow(icon: ImageVector, title: String, subtitle: String, tint: Color = LS.PrimarySoft, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = LS.Text, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = LS.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = LS.Faint)
    }
}

@Composable
fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onChange(!checked) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(LS.PrimarySoft.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LS.PrimarySoft, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = LS.Text, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = LS.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = LS.Primary, checkedThumbColor = Color.White, uncheckedTrackColor = LS.Surface3, uncheckedBorderColor = LS.Stroke),
        )
    }
}

fun isIgnoringBattery(ctx: Context): Boolean =
    ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

@SuppressLint("BatteryLife")
fun requestBattery(ctx: Context) {
    try {
        ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
    } catch (_: Exception) {
        ctx.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}
