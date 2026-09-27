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
import applock.app.security.ProtectionPolicy

private const val PRIVACY_POLICY_URL = "https://thrinetratech.in/applock-privacy-policy"
private const val SUPPORT_EMAIL = "contact@thrinetratech.in"

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    if (!app.repository.hasCredential()) {
        SettingsEditor()
        return
    }
    val authenticated = remember { mutableStateOf(false) }
    if (authenticated.value) SettingsEditor()
    else applock.app.ui.lock.SecurityGateScreen { authenticated.value = true }
}

@Composable
private fun SettingsEditor() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    Column(
        modifier = Modifier.fillMaxWidth().imePadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Manage AppLock's system access, protection controls, privacy information, and support.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader({ Icon(Icons.Default.AdminPanelSettings, null) }, "Security & system controls",
                    "These controls affect how AppLock integrates with Android.")
                ActionButton({ Icon(Icons.Default.Settings, null) }, "Accessibility access",
                    "Manage the Android accessibility service used for foreground-app detection.") {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                ActionButton({ Icon(Icons.Default.Lock, null) }, "Lock all protected apps now",
                    "Immediately clear active unlock sessions and foreground authorization.") {
                    app.repository.clearAllUnlocks(); app.lockEngine.resetTransitionState(); app.repository.refreshProtectionState()
                }
                ActionButton({ Icon(Icons.Default.OpenInNew, null) }, "App system settings",
                    "Open Android's system settings page for AppLock.") {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")))
                }
            }
        }
        ProtectionModeCard()
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader({ Icon(Icons.Default.PrivacyTip, null) }, "Privacy & support",
                    "Information about AppLock's privacy model and how to get help.")
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Privacy-first protection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("AppLock does not intentionally transmit PINs, patterns, authentication secrets or private screen contents for the core locking function.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) } }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PrivacyTip, null); Text("Privacy policy", Modifier.padding(start = 8.dp))
                }
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.SupportAgent, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        Column(Modifier.weight(1f)) {
                            Text("Support", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(SUPPORT_EMAIL, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtectionModeCard() {
    val context = LocalContext.current
    val devicePolicyManager = remember(context) { context.getSystemService(DevicePolicyManager::class.java) }
    var deviceAdminActive by remember { mutableStateOf(ProtectionPolicy.isDeviceAdminActive(context)) }
    var mode by remember { mutableStateOf(applock.app.ui.ProtectionModeState.get(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                deviceAdminActive = ProtectionPolicy.isDeviceAdminActive(context)
                mode = applock.app.ui.ProtectionModeState.get(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val deviceAdminComponent = remember(context) { ProtectionPolicy.deviceAdminComponent(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        deviceAdminActive = ProtectionPolicy.isDeviceAdminActive(context)
        if (ProtectionPolicy.selectEnhancedIfAvailable(context)) mode = applock.app.ui.ProtectionModeState.Mode.ENHANCED
    }
    fun requestEnhanced() {
        if (ProtectionPolicy.selectEnhancedIfAvailable(context)) {
            mode = applock.app.ui.ProtectionModeState.Mode.ENHANCED
        } else {
            launcher.launch(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, deviceAdminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Enable AppLock device protection to strengthen the selected Enhanced Protection mode.")
            })
        }
    }
    fun switchToStandard() {
        ProtectionPolicy.selectStandard(context)
        mode = applock.app.ui.ProtectionModeState.Mode.STANDARD
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionHeader({ Icon(Icons.Default.Security, null) }, "Protection mode",
                when {
                    mode == applock.app.ui.ProtectionModeState.Mode.ENHANCED && deviceAdminActive -> "Enhanced Protection - Accessibility + Device Admin"
                    mode == applock.app.ui.ProtectionModeState.Mode.ENHANCED -> "Enhanced Protection selected - Device Admin needs to be re-enabled"
                    else -> "Standard Protection - Accessibility only"
                })
            Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                color = if (deviceAdminActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (deviceAdminActive) Icons.Default.CheckCircle else Icons.Default.AdminPanelSettings, null)
                    Text(when {
                        mode == applock.app.ui.ProtectionModeState.Mode.ENHANCED && deviceAdminActive -> "Enhanced Protection is active - Device Admin is enabled"
                        mode == applock.app.ui.ProtectionModeState.Mode.ENHANCED -> "Enhanced Protection selected - Device Admin needs to be re-enabled"
                        deviceAdminActive -> "Device Admin is enabled (Standard Protection selected)"
                        else -> "Device Admin is not enabled (Standard Protection)"
                    }, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (mode == applock.app.ui.ProtectionModeState.Mode.ENHANCED) {
                if (!deviceAdminActive) {
                    Button(onClick = { requestEnhanced() }, Modifier.fillMaxWidth()) { Text("Re-enable Device Admin") }
                }
                OutlinedButton(onClick = { switchToStandard() }, Modifier.fillMaxWidth()) { Text("Switch to Standard Protection") }
            } else {
                Button(onClick = { requestEnhanced() }, Modifier.fillMaxWidth()) {
                    Text(if (deviceAdminActive) "Switch to Enhanced Protection" else "Enable & switch to Enhanced Protection")
                }
            }
            Text("Switching modes does not change your PIN, pattern, biometric or accessibility protection setup, and does not remove Device Admin if it is already enabled.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionHeader(icon: @Composable () -> Unit, title: String, description: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
            androidx.compose.foundation.layout.Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionButton(icon: @Composable () -> Unit, title: String, description: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        icon()
        Column(Modifier.weight(1f).padding(start = 10.dp), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
