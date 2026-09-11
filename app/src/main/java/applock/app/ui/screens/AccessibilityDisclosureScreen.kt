package applock.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication

@Composable
fun AccessibilityDisclosureScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    var accepted by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Why App Lock needs Accessibility access", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("App Lock uses Android Accessibility Service to detect when a protected app is opened and present the App Lock authentication interface.\n\nThis access is used for App Lock protection. App Lock does not intentionally collect or transmit private messages, passwords, photos, or other private screen content for this purpose.\n\nYou can disable this access at any time from Android Settings.")
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Checkbox(accepted, { accepted = it }); Text("I understand and agree to enable this access.") }
        Spacer(Modifier.height(12.dp))
        Button(enabled = accepted, onClick = { app.repository.setDisclosureAccepted(true); context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); onDone() }, Modifier.fillMaxWidth()) { Text("Continue to Android Settings") }
    }
}
