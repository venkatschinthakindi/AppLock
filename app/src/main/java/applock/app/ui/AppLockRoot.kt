package applock.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import applock.app.AppLockApplication
import applock.app.ui.components.AppDrawer
import applock.app.ui.screens.AboutScreen
import applock.app.ui.screens.AccessibilityDisclosureScreen
import applock.app.ui.screens.CustomizationScreen
import applock.app.ui.screens.HomeScreen
import applock.app.ui.screens.OnboardingScreen
import applock.app.ui.screens.ProtectionHealthScreen
import applock.app.ui.screens.ProtectedAppsScreen
import applock.app.ui.screens.SecuritySetupScreen
import applock.app.ui.screens.SettingsScreen
import applock.app.ui.screens.SmartLockScreen
import applock.app.ui.screens.SubscriptionScreen
import applock.app.ui.theme.AppLockTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockRoot() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    val theme by app.repository.theme.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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

            "onboarding" -> {
                OnboardingScreen {
                    firstRunStep = "disclosure"
                }
            }

            "disclosure" -> {
                AccessibilityDisclosureScreen {
                    app.repository.setOnboardingComplete(true)
                    firstRunStep = "done"
                }
            }

            else -> {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        AppDrawer(
                            currentDestination = destination,
                            onDestination = { selectedDestination ->
                                destination = selectedDestination

                                scope.launch {
                                    drawerState.close()
                                }
                            }
                        )
                    }
                ) {
                    Scaffold(
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(screenTitle(destination))
                                },
                                navigationIcon = {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                drawerState.open()
                                            }
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Menu,
                                            contentDescription = "Open menu"
                                        )
                                    }
                                }
                            )
                        }
                    ) { paddingValues ->

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                        ) {
                            when (destination) {

                                "home" -> {
                                    HomeScreen(
                                        onNavigate = { target ->
                                            destination = target
                                        }
                                    )
                                }

                                "apps" -> ProtectedAppsScreen()

                                "health" -> ProtectionHealthScreen()

                                "smart" -> SmartLockScreen()

                                "custom" -> CustomizationScreen()

                                "settings" -> SettingsScreen()

                                "security" -> SecuritySetupScreen()

                                "pro" -> SubscriptionScreen()

                                "about" -> AboutScreen()

                                else -> {
                                    HomeScreen(
                                        onNavigate = { target ->
                                            destination = target
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun screenTitle(destination: String): String =
    when (destination) {
        "apps" -> "Protected apps"
        "health" -> "Protection Health"
        "smart" -> "Smart Lock"
        "custom" -> "Customization"
        "settings" -> "Settings"
        "security" -> "Authentication"
        "pro" -> "AppLock Pro"
        "about" -> "About AppLock"
        else -> "AppLock"
    }