package applock.app.ui.screens

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import applock.app.AppLockApplication
import applock.app.security.AppLockDeviceAdminReceiver
import applock.app.ui.ProtectionModeState

private const val PRIVACY_POLICY_URL =
    "https://thrinetratech.in/applock-privacy-policy"

private const val SUPPORT_EMAIL =
    "contact@thrinetratech.in"

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication

    if (!app.repository.hasCredential()) {
        SettingsEditor()
        return
    }

        val authenticated = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    if (authenticated.value) {
        SettingsEditor()
    } else {
        applock.app.ui.lock.SecurityGateScreen(
            onAuthenticated = {
                authenticated.value = true
            }
        )
    }
}
@Composable
private fun SettingsEditor() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Manage AppLock's system access, protection controls, privacy information, and support.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionHeader(
                    icon = {
                        Icon(
                            Icons.Default.AdminPanelSettings,
                            contentDescription = null
                        )
                    },
                    title = "Security & system controls",
                    description = "These controls affect how AppLock integrates with Android."
                )

                ActionButton(
                    icon = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = null
                        )
                    },
                    title = "Accessibility access",
                    description = "Manage the Android accessibility service used for foreground-app detection."
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                }

                ActionButton(
                    icon = {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null
                        )
                    },
                    title = "Lock all protected apps now",
                    description = "Immediately clear active unlock sessions and foreground authorization."
                ) {
                    app.repository.clearAllUnlocks()
                    app.lockEngine.resetTransitionState()
                    app.repository.refreshProtectionState()
                }

                ActionButton(
                    icon = {
                        Icon(
                            Icons.Default.OpenInNew,
                            contentDescription = null
                        )
                    },
                    title = "App system settings",
                    description = "Open Android's system settings page for AppLock."
                ) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            }
        }

        ProtectionModeCard()

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionHeader(
                    icon = {
                        Icon(
                            Icons.Default.PrivacyTip,
                            contentDescription = null
                        )
                    },
                    title = "Privacy & support",
                    description = "Information about AppLock's privacy model and how to get help."
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "Privacy-first protection",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "AppLock does not intentionally transmit PINs, patterns, authentication secrets or private screen contents for the core locking function.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(PRIVACY_POLICY_URL)
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.PrivacyTip,
                        contentDescription = null
                    )
                    Text(
                        "Privacy policy",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.SupportAgent,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Support",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                SUPPORT_EMAIL,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lets the user view and change AppLock's Protection Mode from
 * Settings.
 *
 * Mirrors the exact gating rules used during first-run in
 * `AppLockRoot`:
 *
 *  - Standard -> Enhanced with Device Admin already active: persist
 *    immediately.
 *  - Standard -> Enhanced with Device Admin inactive: launch
 *    Android's confirmation first, and persist ENHANCED only in the
 *    launcher callback once isAdminActive() actually returns true.
 *    Cancelling leaves the mode as Standard - nothing is persisted.
 *  - Enhanced -> Standard: persist immediately. Device Admin is left
 *    enabled; it is an Android-managed capability and is not
 *    silently revoked just because AppLock's own mode changed.
 */
@Composable
private fun ProtectionModeCard() {
    val context = LocalContext.current

    val devicePolicyManager =
        remember(context) {
            context.getSystemService(
                DevicePolicyManager::class.java
            )
        }

    val deviceAdminComponent =
        remember(context) {
            ComponentName(
                context,
                AppLockDeviceAdminReceiver::class.java
            )
        }

    var deviceAdminActive by remember {
        mutableStateOf(
            devicePolicyManager.isAdminActive(
                deviceAdminComponent
            )
        )
    }

    var mode by remember {
        mutableStateOf(
            ProtectionModeState.get(context)
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current

    /*
     * Re-check Device Admin whenever this screen resumes (e.g. after
     * returning from Android's confirmation screen or the device
     * admin system settings page).
     */
    DisposableEffect(
        lifecycleOwner,
        devicePolicyManager,
        deviceAdminComponent
    ) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    deviceAdminActive =
                        devicePolicyManager.isAdminActive(
                            deviceAdminComponent
                        )
                }
            }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val enhancedDeviceAdminLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.StartActivityForResult()
        ) {
            deviceAdminActive =
                devicePolicyManager.isAdminActive(
                    deviceAdminComponent
                )

            if (deviceAdminActive) {
                ProtectionModeState.set(
                    context,
                    ProtectionModeState.Mode.ENHANCED
                )
                mode = ProtectionModeState.Mode.ENHANCED
            }
            /*
             * If cancelled, deviceAdminActive stays false and mode
             * is intentionally left as Standard - nothing persisted.
             */
        }

    fun requestEnhanced() {
        if (deviceAdminActive) {
            ProtectionModeState.set(
                context,
                ProtectionModeState.Mode.ENHANCED
            )
            mode = ProtectionModeState.Mode.ENHANCED
        } else {
            val intent =
                Intent(
                    DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
                ).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        deviceAdminComponent
                    )
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Enable AppLock device protection " +
                            "to strengthen tamper and " +
                            "uninstall protection."
                    )
                }

            enhancedDeviceAdminLauncher.launch(intent)
        }
    }

    fun switchToStandard() {
        ProtectionModeState.set(
            context,
            ProtectionModeState.Mode.STANDARD
        )
        mode = ProtectionModeState.Mode.STANDARD
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                icon = {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null
                    )
                },
                title = "Protection mode",
                description =
                    if (mode == ProtectionModeState.Mode.ENHANCED) {
                        "Enhanced Protection - Accessibility + Device Admin"
                    } else {
                        "Standard Protection - Accessibility only"
                    }
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color =
                    if (deviceAdminActive) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (deviceAdminActive) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.AdminPanelSettings
                        },
                        contentDescription = null
                    )
                    Text(
                        if (deviceAdminActive) {
                            "Device Admin is currently enabled"
                        } else {
                            "Device Admin is not enabled"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (mode == ProtectionModeState.Mode.ENHANCED) {
                OutlinedButton(
                    onClick = { switchToStandard() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Switch to Standard Protection")
                }
            } else {
                Button(
                    onClick = { requestEnhanced() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (deviceAdminActive) {
                            "Switch to Enhanced Protection"
                        } else {
                            "Enable & switch to Enhanced Protection"
                        }
                    )
                }
            }

            Text(
                "Switching modes does not change your PIN, pattern, " +
                    "biometric or accessibility protection setup, and " +
                    "does not remove Device Admin if it is already " +
                    "enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionHeader(
    icon: @Composable () -> Unit,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        icon()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}