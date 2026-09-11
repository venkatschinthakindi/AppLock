package applock.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    var drawer by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)

    LaunchedEffect(drawer) {
        if (drawer) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    var destination by remember { mutableStateOf("home") }

    var firstRunStep by remember {
        mutableStateOf(
            if (!app.repository.isOnboardingComplete()) {
                "onboarding"
            } else if (!app.repository.disclosureAccepted()) {
                "disclosure"
            } else {
                "done"
            }
        )
    }

    AppLockTheme(theme) {

        if (firstRunStep == "onboarding") {
            OnboardingScreen {
                firstRunStep = "disclosure"
            }
            return@AppLockTheme
        }

        if (firstRunStep == "disclosure") {
            AccessibilityDisclosureScreen {
                app.repository.setOnboardingComplete(true)
                firstRunStep = "done"
            }
            return@AppLockTheme
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                AppDrawer {
                    destination = it
                    drawer = false
                }
            }
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                when (destination) {
                                    "home" -> "App Lock"
                                    "apps" -> "Protected apps"
                                    "health" -> "Protection Health"
                                    "smart" -> "Smart Lock"
                                    "custom" -> "Customization"
                                    "settings" -> "Settings"
                                    "security" -> "Authentication"
                                    "pro" -> "App Lock Pro"
                                    else -> "App Lock"
                                }
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = {
                                    drawer = true
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = "Menu"
                                )
                            }
                        }
                    )
                }
            ) { paddingValues ->

                Box(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize()
                ) {
                    when (destination) {
                        "home" -> HomeScreen()
                        "apps" -> ProtectedAppsScreen()
                        "health" -> ProtectionHealthScreen()
                        "smart" -> SmartLockScreen()
                        "custom" -> CustomizationScreen()
                        "settings" -> SettingsScreen()
                        "security" -> SecuritySetupScreen()
                        "pro" -> SubscriptionScreen()
                    }
                }
            }
        }
    }
}