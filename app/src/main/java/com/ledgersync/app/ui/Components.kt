package com.ledgersync.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.ledgersync.app.data.SyncState
import com.ledgersync.app.data.TxnEntity

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .clip(shape)
            .background(LS.Surface)
            .border(1.dp, LS.Stroke, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = LS.Text, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(
                action, style = MaterialTheme.typography.labelLarge, color = LS.PrimarySoft,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(6.dp)
            )
        }
    }
}

/** Smoothly counts from the previous value to the new one. */
@Composable
fun animatedDouble(target: Double, durationMs: Int = 900): Double {
    val progress = remember { Animatable(0f) }
    var from by remember { mutableStateOf(0.0) }
    var to by remember { mutableStateOf(target) }
    LaunchedEffect(target) {
        from += (to - from) * progress.value
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return from + (to - from) * progress.value
}

@Composable
fun BankAvatar(name: String, size: Dp = 44.dp) {
    val c = LS.avatar(name)
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(c.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" },
            color = c, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp,
        )
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text, color = color, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
fun PulseDot(color: Color) {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, 2.2f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "s")
    val a by t.animateFloat(0.5f, 0f, infiniteRepeatable(tween(1400), RepeatMode.Restart), label = "a")
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).scale(s).clip(CircleShape).background(color.copy(alpha = a)))
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
    }
}

@Composable
fun SyncPill(pending: Int, failed: Int, syncing: Boolean, connected: Boolean, onClick: () -> Unit) {
    val (label, color) = when {
        !connected -> "Not connected" to LS.Debit
        syncing -> "Syncing…" to LS.Cyan
        failed > 0 -> "$pending waiting" to LS.Amber
        pending > 0 -> "$pending queued" to LS.Amber
        else -> "Live" to LS.Credit
    }
    val bg by animateColorAsState(color.copy(alpha = 0.14f), label = "bg")
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (syncing) CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 2.dp)
        else PulseDot(color)
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun TxnIcon(credit: Boolean, size: Dp = 42.dp) {
    val c = if (credit) LS.Credit else LS.Debit
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(c.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(if (credit) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast, null, tint = c, modifier = Modifier.size(size / 2))
    }
}

@Composable
fun SyncBadge(state: Int) {
    val (icon, color) = when (state) {
        SyncState.SYNCED, SyncState.DUPLICATE -> Icons.Rounded.CloudDone to LS.Credit.copy(alpha = 0.8f)
        SyncState.FAILED -> Icons.Rounded.ErrorOutline to LS.Amber
        else -> Icons.Rounded.CloudUpload to LS.Muted
    }
    Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
}

@Composable
fun TxnRow(t: TxnEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TxnIcon(t.isCredit)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.party.ifBlank { if (t.isCredit) "Money received" else "Payment" },
                style = MaterialTheme.typography.titleSmall, color = LS.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${t.accountNick} · ${t.mode} · ${timeOf(t.timestamp)}",
                style = MaterialTheme.typography.bodySmall, color = LS.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (t.isCredit) "+" else "−") + formatInr(t.amount),
                color = if (t.isCredit) LS.Credit else LS.Text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(3.dp))
            SyncBadge(t.syncState)
        }
    }
}

@Composable
fun ChoiceChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(LS.Surface).border(1.dp, LS.Stroke, RoundedCornerShape(16.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            val bg by animateColorAsState(if (active) LS.Primary else Color.Transparent, tween(250), label = "chip")
            val fg by animateColorAsState(if (active) Color.White else LS.Muted, tween(250), label = "chipfg")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(bg).clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        }
    }
}

@Composable
fun FilterChipPill(label: String, active: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (active) LS.Primary.copy(alpha = 0.22f) else LS.Surface, label = "f")
    val fg by animateColorAsState(if (active) LS.PrimarySoft else LS.Muted, label = "ff")
    Text(
        label, color = fg, style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (active) LS.Primary.copy(alpha = 0.5f) else LS.Stroke, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
fun LsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    visual: VisualTransformation = VisualTransformation.None,
    leading: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth(),
        singleLine = singleLine, minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visual,
        leadingIcon = leading?.let { { Icon(it, null, tint = LS.Muted) } },
        trailingIcon = trailing,
        supportingText = supporting?.let { { Text(it, color = LS.Faint) } },
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LS.Primary, unfocusedBorderColor = LS.Stroke,
            focusedContainerColor = LS.Surface, unfocusedContainerColor = LS.Surface,
            focusedLabelColor = LS.PrimarySoft, unfocusedLabelColor = LS.Muted,
            cursorColor = LS.Primary, focusedTextColor = LS.Text, unfocusedTextColor = LS.Text,
        ),
    )
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, icon: ImageVector? = null) {
    Button(
        onClick = onClick, enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LS.Primary, contentColor = Color.White, disabledContainerColor = LS.Surface3, disabledContentColor = LS.Faint),
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else {
            if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, fontSize = 15.sp)
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Surface(
        onClick = onClick, enabled = enabled, modifier = modifier.height(48.dp), shape = RoundedCornerShape(14.dp),
        color = LS.Surface2, contentColor = LS.Text, border = BorderStroke(1.dp, LS.Stroke),
    ) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, Modifier.size(18.dp), tint = LS.PrimarySoft); Spacer(Modifier.width(8.dp)) }
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) LS.Text else LS.Faint)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(LS.Primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = LS.PrimarySoft, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = LS.Text)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LS.Muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onAction) { Icon(Icons.Rounded.Sync, null); Spacer(Modifier.width(6.dp)); Text(action) }
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = LS.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(key, color = LS.Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(120.dp))
        Text(value.ifBlank { "—" }, color = valueColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
