package applock.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    if (app.repository.hasCredential()) {
        SecurityGateScreen(
            title = "AppLock settings are secured",
            description = "Authenticate before changing system-access paths or security state."
        ) {
            SettingsEditor()
        }
    } else {
        SettingsEditor()
    }
}

@Composable
private fun SettingsEditor() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("System controls and privacy information stay separate from authentication.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, Modifier.fillMaxWidth()) { Text("Accessibility access") }
        OutlinedButton(onClick = { app.repository.clearAllUnlocks() }, Modifier.fillMaxWidth()) { Text("Lock all protected apps now") }
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, Modifier.fillMaxWidth()) { Text("App system settings") }
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://thrinetratech.in/privacy-policy"))) }, Modifier.fillMaxWidth()) { Text("Privacy policy") }
        Text("Support", style = MaterialTheme.typography.titleMedium)
        Text("contact@thrinetratech.in", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("AppLock does not intentionally transmit PINs, patterns, authentication secrets or private screen contents for the core locking function.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
