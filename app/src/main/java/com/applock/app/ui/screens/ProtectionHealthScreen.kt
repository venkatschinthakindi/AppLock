package applock.app.ui.screens

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication
import applock.app.domain.HealthState

@Composable
fun ProtectionHealthScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val accessibility = app.repository.accessibilityEnabled()
    val hasAuth = app.repository.hasPin() || app.repository.hasPattern()
    val power = context.getSystemService(PowerManager::class.java)
    val batteryOk = power?.isIgnoringBatteryOptimizations(context.packageName) == true
    val checks = listOf(
        Triple("Lock Engine", if (accessibility) HealthState.GREEN else HealthState.YELLOW, if (accessibility) "Detection service is enabled." else "Detection service is unavailable."),
        Triple("Authentication", if (hasAuth || app.repository.getAuthMethod() == applock.app.domain.AuthMethod.BIOMETRIC) HealthState.GREEN else HealthState.RED, "A configured authentication method is required."),
        Triple("Protected Apps", if (app.repository.protectedPackages().isNotEmpty()) HealthState.GREEN else HealthState.YELLOW, "${app.repository.protectedPackages().size} apps selected."),
        Triple("Battery Restrictions", if (batteryOk) HealthState.GREEN else HealthState.YELLOW, if (batteryOk) "No app-specific battery restriction detected." else "Battery optimization may limit background reliability on some devices.")
    )
    val overall = if (checks.any { it.second == HealthState.RED }) "Not protected" else if (checks.any { it.second == HealthState.YELLOW }) "Limited protection" else "Protected"
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(overall, style = MaterialTheme.typography.headlineMedium)
        checks.forEach { (title, state, detail) -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant); if (title == "Lock Engine" && state != HealthState.GREEN) { Spacer(Modifier.height(8.dp)); Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open settings") } } } } }
    }
}
