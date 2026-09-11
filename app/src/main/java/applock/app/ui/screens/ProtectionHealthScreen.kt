package applock.app.ui.screens

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication
import applock.app.domain.*
import applock.app.ui.components.PremiumCard
import applock.app.ui.components.StatusPill

@Composable
fun ProtectionHealthScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val snapshot by app.repository.protectionSnapshot.collectAsState()
    val method = app.repository.getAuthMethod()
    val biometricReady = BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS
    val authReady = when (method) {
        AuthMethod.PIN -> app.repository.hasPin()
        AuthMethod.PATTERN -> app.repository.hasPattern()
        AuthMethod.BIOMETRIC -> biometricReady && app.repository.hasPin()
    }
    val power = context.getSystemService(PowerManager::class.java)
    val batteryOk = power?.isIgnoringBatteryOptimizations(context.packageName) == true
    val checks = listOf(
        HealthRow("Secure storage", if (snapshot.secureStorageHealthy) HealthState.GREEN else HealthState.RED,
            if (snapshot.secureStorageHealthy) "Keystore-backed security data is readable." else "Encrypted security data cannot be trusted."),
        HealthRow("Lock Engine", if (snapshot.accessibilityEnabled && snapshot.credentialConfigured) HealthState.GREEN else HealthState.RED,
            if (snapshot.accessibilityEnabled && snapshot.credentialConfigured) "Detection and authentication are available." else "A required protection component is unavailable."),
        HealthRow("Authentication", if (authReady) HealthState.GREEN else HealthState.RED,
            if (authReady) "${method.name.lowercase().replaceFirstChar { it.uppercase() }} is configured." else "Configure a working authentication method."),
        HealthRow("Protected Apps", if (snapshot.protectedAppCount > 0) HealthState.GREEN else HealthState.YELLOW,
            if (snapshot.protectedAppCount > 0) "${snapshot.protectedAppCount} app(s) selected for protection." else "No apps are selected for protection yet."),
        HealthRow("Accessibility", if (snapshot.accessibilityEnabled) HealthState.GREEN else HealthState.RED,
            if (snapshot.accessibilityEnabled) "Android reports the AppLock service as enabled." else "Enable AppLock in Android Accessibility settings."),
        HealthRow("Battery Restrictions", if (batteryOk) HealthState.GREEN else HealthState.YELLOW,
            if (batteryOk) "No app-specific battery optimization detected." else "Some OEMs may restrict background detection."),
        HealthRow("Boot Recovery", if (snapshot.accessibilityEnabled && snapshot.credentialConfigured) HealthState.GREEN else HealthState.RED,
            "Previous unlock sessions are discarded after restart; protection is not claimed before the service is available.")
    )
    val overallState = when {
        checks.any { it.state == HealthState.RED } -> HealthState.RED
        checks.any { it.state == HealthState.YELLOW } -> HealthState.YELLOW
        else -> HealthState.GREEN
    }
    val overall = when (overallState) {
        HealthState.GREEN -> "Protected"
        HealthState.YELLOW -> "Limited protection"
        HealthState.RED -> "Not protected"
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp)) {
                Icon(Icons.Default.HealthAndSafety, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(overall, style = MaterialTheme.typography.headlineMedium)
                Text(when (snapshot.reason) {
                    ProtectionReason.PROTECTED -> "All required protection components are available."
                    ProtectionReason.NO_PROTECTED_APPS -> "Authentication and detection are ready, but no protected apps are selected."
                    ProtectionReason.ACCESSIBILITY_DISABLED -> "Protection is disabled because Android Accessibility is not enabled."
                    ProtectionReason.SECURITY_SETUP_REQUIRED -> "Security setup is incomplete."
                    ProtectionReason.SECURE_STORAGE_ERROR -> "Encrypted AppLock security data cannot be trusted."
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (snapshot.forceStopRecoveryPending) {
                    Spacer(Modifier.height(8.dp))
                    Text("A previous force-stop was detected. Unlock sessions were cleared.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(10.dp))
                StatusPill(if (overallState == HealthState.GREEN) "Healthy" else "Action required", overallState == HealthState.GREEN)
            }
        }
        checks.forEach { check ->
            PremiumCard(title = check.title, subtitle = check.detail) {
                if ((check.title == "Lock Engine" || check.title == "Accessibility") && !snapshot.accessibilityEnabled) {
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Enable Accessibility") }
                }
            }
        }
    }
}
private data class HealthRow(val title: String, val state: HealthState, val detail: String)
