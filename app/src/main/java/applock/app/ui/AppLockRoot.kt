package applock.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import applock.app.AppLockApplication
import applock.app.ui.components.AppDrawer
import applock.app.ui.screens.*
import applock.app.ui.theme.AppLockTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockRoot() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    val theme by app.repository.theme.collectAsState()
    var drawerOpen by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(drawerOpen) {
        if (drawerOpen) drawerState.open() else drawerState.close()
    }

    var destination by remember { mutableStateOf("home") }
    var firstRunStep by remember {
        mutableStateOf(
            when {
                !app.repository.isOnboardingComplete() -> "onboarding"
                !app.repository.disclosureAccepted() -> "disclosure"
                else -> "done"
            }
        )
    }

    AppLockTheme(theme) {
        when (firstRunStep) {
            "onboarding" -> OnboardingScreen { firstRunStep = "disclosure" }
            "disclosure" -> AccessibilityDisclosureScreen {
                app.repository.setOnboardingComplete(true)
                firstRunStep = "done"
            }
            else -> {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        AppDrawer(
                            currentDestination = destination,
                            onDestination = {
                                destination = it
                                drawerOpen = false
                            }
                        )
                    }
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = { androidx.compose.material3.Text(screenTitle(destination)) },
                                navigationIcon = {
                                    IconButton(onClick = { drawerOpen = true }) {
                                        Icon(Icons.Default.Menu, contentDescription = "Open menu")
                                    }
                                }
                            )
                        }
                    ) { paddingValues ->
                        Box(
                            Modifier
                                .padding(paddingValues)
                                .fillMaxSize()
                        ) {
                            when (destination) {
                                "home" -> HomeScreen(onSetupProtection = { destination = "security" })
                                "apps" -> ProtectedAppsScreen()
                                "health" -> ProtectionHealthScreen()
                                "smart" -> SmartLockScreen()
                                "custom" -> CustomizationScreen()
                                "settings" -> SettingsScreen()
                                "security" -> SecuritySetupScreen()
                                "pro" -> SubscriptionScreen()
                                "about" -> AboutScreen()
                                else -> HomeScreen()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun screenTitle(destination: String): String = when (destination) {
    "apps" -> "Protected apps"
    "health" -> "Protection Health"
    "smart" -> "Smart Lock"
    "custom" -> "Customization"
    "settings" -> "Settings"
    "security" -> "Authentication"
    "pro" -> "App Lock Pro"
    "about" -> "About AppLock"
    else -> "AppLock"
}
