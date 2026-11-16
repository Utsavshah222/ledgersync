package com.ledgersync.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/**
 * Staff edition home. Deliberately shows NO money data — no balances, no history, no account totals.
 * Its only job is to reassure whoever set the phone up that bank SMS are being captured and sent to the sheet,
 * and to offer the few setup controls a staff phone needs.
 */
@Composable
fun StaffScreen(vm: MainViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }
    AnimatedContent(
        targetState = showSettings,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        label = "staff",
    ) { settings ->
        if (settings) StaffSettings(vm) { showSettings = false } else StaffStatus(vm) { showSettings = true }
    }
}

@Composable
private fun StaffStatus(vm: MainViewModel, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val s = vm.settings
    vm.settings.version.collectAsState().value
    val pending by vm.pending.collectAsState()
    val syncing by vm.syncing.collectAsState()
    var batteryOk by remember { mutableStateOf(isIgnoringBattery(ctx)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryOk = isIgnoringBattery(ctx) }

    val connected = s.isConnected

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp).padding(top = 12.dp, bottom = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LedgerSync Staff", style = MaterialTheme.typography.headlineMedium, color = LS.Text)
                Text("Device: ${s.deviceName}", style = MaterialTheme.typography.bodyMedium, color = LS.Muted)
            }
            IconButton(onSettings) { Icon(Icons.Rounded.Tune, "Settings", tint = LS.PrimarySoft) }
        }

        Spacer(Modifier.height(20.dp))

        // Big "it's working" badge
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(LS.Hero).padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(84.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(
                        if (connected) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                        null, tint = Color.White, modifier = Modifier.size(46.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    if (connected) "Capturing bank SMS" else "Finish setup",
                    color = Color.White, style = MaterialTheme.typography.titleLarge,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (connected) "Every bank message on this phone is sent to the company sheet automatically — even when this app is closed."
                    else "Connect this phone to the company Google Sheet so it can start sending bank SMS.",
                    color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        GlassCard {
            StaffStatRow(
                Icons.Rounded.CloudDone,
                if (connected) "Connected to the sheet" else "Not connected",
                if (s.lastSyncError != null) "Last error: ${s.lastSyncError}" else "Last sync ${relativeTime(s.lastSyncAt)}",
                if (connected) LS.Credit else LS.Amber,
            )
            Spacer(Modifier.height(4.dp))
            StaffStatRow(
                Icons.Rounded.Sync,
                if (syncing) "Sending now…" else if (pending > 0) "$pending waiting to upload" else "All sent",
                "Uploads happen automatically, and retry on their own",
                if (pending > 0) LS.Amber else LS.Credit,
            )
        }

        Spacer(Modifier.height(16.dp))
        PrimaryButton("Sync now", { vm.syncNow(true) }, loading = syncing, icon = Icons.Rounded.Sync)

        SectionHeader("Keep it running")
        GlassCard(padding = 6.dp) {
            ActionRow(
                Icons.Rounded.BatteryChargingFull,
                if (batteryOk) "Battery: unrestricted ✓" else "Allow background running",
                if (batteryOk) "Android won't put this app to sleep" else "Recommended — stops Android from delaying uploads",
                tint = if (batteryOk) LS.Credit else LS.Amber,
            ) { requestBattery(ctx) }
            ActionRow(Icons.Rounded.RocketLaunch, "Autostart & app settings", "Xiaomi / Vivo / Oppo / Realme: enable Autostart") {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
            }
            ActionRow(Icons.Rounded.Tune, "Connection & setup", "Change the sheet link, import old SMS, re-run setup") { onSettings() }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Staff edition · shows no balances or history · data goes only to your company Google Sheet",
            color = LS.Faint, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StaffStatRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, tint: Color) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = LS.Text, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = LS.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StaffSettings(vm: MainViewModel, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 8.dp, top = 8.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = LS.Text) }
            Text("Connection & setup", style = MaterialTheme.typography.titleLarge, color = LS.Text)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Rounded.Shield, null, tint = LS.PrimarySoft, modifier = Modifier.padding(end = 4.dp))
        }
        // Settings has no money data, so it's safe to reuse in the Staff edition for connection, import and reliability.
        Box(Modifier.weight(1f)) { SettingsScreen(vm) }
    }
}
