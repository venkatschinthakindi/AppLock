package applock.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication

@Composable
fun AccessibilityDisclosureScreen(
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication

    var accepted by remember {
        mutableStateOf(false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Why App Lock needs Accessibility access",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        Text(
            text = "App Lock uses Android Accessibility Service to detect when a protected app is opened and present the App Lock authentication interface.\n\n" +
                "This access is used for App Lock protection. App Lock does not intentionally collect or transmit private messages, passwords, photos, or other private screen content for this purpose.\n\n" +
                "You can disable this access at any time from Android Settings."
        )

        Spacer(
            modifier = Modifier.height(16.dp)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(
                checked = accepted,
                onCheckedChange = {
                    accepted = it
                }
            )

            Text(
                text = "I understand and agree to enable this access."
            )
        }

        Spacer(
            modifier = Modifier.height(12.dp)
        )

        Button(
            enabled = accepted,
            onClick = {
                app.repository.setDisclosureAccepted(true)
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
                onDone()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue to Android Settings")
        }
    }
}
