package com.ledgersync.app.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LedgerTheme { AppRoot() } }
    }
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val version = vm.settings.version.collectAsState().value
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.events.collect { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show() } }

    Box(Modifier.fillMaxSize().background(LS.Bg)) {
        AnimatedContent(
            targetState = version >= 0 && vm.settings.onboarded,
            transitionSpec = { (fadeIn(tween(500)) + scaleIn(initialScale = 0.96f)) togetherWith fadeOut(tween(250)) },
            label = "root",
        ) { onboarded ->
            if (onboarded) MainShell(vm) else OnboardingScreen(vm)
        }
    }
}

private data class Tab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("Home", Icons.Rounded.Home),
    Tab("Activity", Icons.Rounded.Receipt),
    Tab("Accounts", Icons.Rounded.AccountBalance),
    Tab("Settings", Icons.Rounded.Tune),
)

@Composable
fun MainShell(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = tab != 0) { tab = 0 }

    Scaffold(
        containerColor = LS.Bg,
        bottomBar = {
            NavigationBar(containerColor = LS.Surface, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White, selectedTextColor = LS.Text,
                            indicatorColor = LS.Primary, unselectedIconColor = LS.Muted, unselectedTextColor = LS.Muted,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        AnimatedContent(
            targetState = tab,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            modifier = Modifier.padding(pad),
            label = "tabs",
        ) { t ->
            when (t) {
                0 -> HomeScreen(vm, onOpenActivity = { tab = 1 }, onOpenAccounts = { tab = 2 }, onOpenSettings = { tab = 3 })
                1 -> ActivityScreen(vm)
                2 -> AccountsScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}

