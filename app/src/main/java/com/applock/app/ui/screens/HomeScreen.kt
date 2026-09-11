package applock.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val count = app.repository.protectedPackages().size
    val service = app.repository.accessibilityEnabled()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text(if (service) "Protection active" else "Limited protection", style = MaterialTheme.typography.headlineSmall); Text(if (service) "$count apps are protected." else "Enable Accessibility access to detect protected apps.", color = MaterialTheme.colorScheme.onSurfaceVariant); if (!service) { Spacer(Modifier.height(12.dp)); Button(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Fix protection") } } } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp)) { Text("Security first", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(8.dp)); Text("Authentication is local-first and does not wait for ads, network, billing, analytics or remote configuration.", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}
