package com.ledgersync.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

object LS {
    val Bg = Color(0xFF0A0E1A)
    val Surface = Color(0xFF121829)
    val Surface2 = Color(0xFF1A2238)
    val Surface3 = Color(0xFF232C45)
    val Stroke = Color(0x1AFFFFFF)
    val Primary = Color(0xFF7C5CFF)
    val PrimarySoft = Color(0xFFB4A3FF)
    val Cyan = Color(0xFF22D3EE)
    val Credit = Color(0xFF2FD574)
    val Debit = Color(0xFFFF5C7A)
    val Amber = Color(0xFFFFB547)
    val Text = Color(0xFFEEF1F8)
    val Muted = Color(0xFF8B95AE)
    val Faint = Color(0xFF5B6580)

    val Hero = Brush.linearGradient(listOf(Color(0xFF6A46FF), Color(0xFF3B6CFF), Color(0xFF12B5D0)))
    val Glow = Brush.radialGradient(listOf(Color(0x557C5CFF), Color(0x007C5CFF)))

    private val avatarPalette = listOf(
        Color(0xFF7C5CFF), Color(0xFF22D3EE), Color(0xFFFF8A4C), Color(0xFF2FD574),
        Color(0xFFFF5CA8), Color(0xFFFFC542), Color(0xFF4C9DFF), Color(0xFFB66DFF),
    )
    fun avatar(seed: String) = avatarPalette[abs(seed.lowercase().hashCode()) % avatarPalette.size]
}

private val Sans = FontFamily.SansSerif

@Composable
fun LedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = LS.Primary, onPrimary = Color.White,
            secondary = LS.Cyan, onSecondary = Color.Black,
            background = LS.Bg, onBackground = LS.Text,
            surface = LS.Surface, onSurface = LS.Text,
            surfaceVariant = LS.Surface2, onSurfaceVariant = LS.Muted,
            surfaceContainer = LS.Surface, surfaceContainerHigh = LS.Surface2, surfaceContainerHighest = LS.Surface3,
            surfaceContainerLow = LS.Surface, surfaceContainerLowest = LS.Bg,
            outline = LS.Faint, outlineVariant = LS.Stroke, error = LS.Debit,
        ),
        typography = Typography(
            displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.5).sp),
            headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.3).sp),
            headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
            titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
            titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
            bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 22.sp),
            bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp),
            labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
            labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
            labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp),
        ),
        content = content,
    )
}

// ---------------- Formatting ----------------

private val inr: NumberFormat = NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply {
    maximumFractionDigits = 2; minimumFractionDigits = 2
}

fun formatInr(v: Double): String = inr.format(v)

/** ₹1.25 Cr · ₹4.8 L · ₹12,400 — for tight spaces. */
fun formatInrShort(v: Double): String {
    val a = abs(v); val s = if (v < 0) "-" else ""
    return when {
        a >= 1_00_00_000 -> s + "₹" + trim(a / 1_00_00_000) + " Cr"
        a >= 1_00_000 -> s + "₹" + trim(a / 1_00_000) + " L"
        else -> s + NumberFormat.getCurrencyInstance(Locale("en", "IN")).apply { maximumFractionDigits = 0 }.format(a)
    }
}

private fun trim(d: Double) = String.format(Locale.ROOT, "%.2f", d).trimEnd('0').trimEnd('.')

fun relativeTime(ms: Long): String {
    if (ms <= 0) return "never"
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(ms))
    }
}

fun timeOf(ms: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ms))
fun dateTimeOf(ms: Long): String = SimpleDateFormat("EEE, d MMM yyyy · h:mm a", Locale.getDefault()).format(Date(ms))

fun dayLabel(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val today = Calendar.getInstance()
    val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        same(c, today) -> "Today"
        same(c, y) -> "Yesterday"
        c.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(Date(ms))
        else -> SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(ms))
    }
}

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}
