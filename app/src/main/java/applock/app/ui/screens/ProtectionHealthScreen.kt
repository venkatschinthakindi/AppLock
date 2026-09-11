package applock.app.ui.screens

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication
import applock.app.domain.AuthMethod
import applock.app.domain.HealthState
import applock.app.ui.components.PremiumCard
import applock.app.ui.components.StatusPill

@Composable
fun ProtectionHealthScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val accessibility = app.repository.accessibilityEnabled()
    val protectedCount = app.repository.protectedPackages().size
    val authMethod = app.repository.getAuthMethod()
    val biometricReady = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    val authReady = when (authMethod) {
        AuthMethod.PIN -> app.repository.hasPin()
        AuthMethod.PATTERN -> app.repository.hasPattern()
        AuthMethod.BIOMETRIC -> biometricReady || app.repository.hasPin()
    }
    val power = context.getSystemService(PowerManager::class.java)
    val batteryOk = power?.isIgnoringBatteryOptimizations(context.packageName) == true

    val checks = listOf(
        Triple("Lock Engine", if (accessibility) HealthState.GREEN else HealthState.YELLOW, if (accessibility) "Detection service is enabled." else "Accessibility detection is unavailable."),
        Triple("Authentication", if (authReady) HealthState.GREEN else HealthState.RED, if (authReady) "${authMethod.name.lowercase().replaceFirstChar { it.uppercase() }} is ready." else "Configure a working authentication method."),
        Triple("Protected Apps", if (protectedCount > 0) HealthState.GREEN else HealthState.YELLOW, "$protectedCount apps selected for protection."),
        Triple("Accessibility", if (accessibility) HealthState.GREEN else HealthState.YELLOW, if (accessibility) "Android reports AppLock as enabled." else "Enable AppLock in Android Accessibility settings."),
        Triple("Battery Restrictions", if (batteryOk) HealthState.GREEN else HealthState.YELLOW, if (batteryOk) "No app-specific battery optimization detected." else "Some OEMs may restrict background detection."),
        Triple("Boot Recovery", HealthState.GREEN, "The app does not claim protection until the detection service is actually available.")
    )
    val overallState = when {
        checks.any { it.second == HealthState.RED } -> HealthState.RED
        checks.any { it.second == HealthState.YELLOW } -> HealthState.YELLOW
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
                androidx.compose.material3.Icon(Icons.Default.HealthAndSafety, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text(overall, style = MaterialTheme.typography.headlineMedium)
                Text("A truthful view of the protection pipeline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                StatusPill(if (overallState == HealthState.GREEN) "Healthy" else "Action recommended", overallState == HealthState.GREEN)
            }
        }
        checks.forEach { (title, state, detail) ->
            PremiumCard(title = title, subtitle = detail) {
                if (title == "Lock Engine" && state != HealthState.GREEN || title == "Accessibility" && state != HealthState.GREEN) {
                    Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility settings") }
                }
            }
        }
    }
}
