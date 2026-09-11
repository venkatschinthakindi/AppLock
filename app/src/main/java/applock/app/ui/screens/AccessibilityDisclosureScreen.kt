package applock.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication

@Composable
fun AccessibilityDisclosureScreen(
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication

    var accepted by remember { mutableStateOf(false) }

    val background = MaterialTheme.colorScheme.background
    val primary = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onBackground
    val secondaryText = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(background)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Why App Lock needs Accessibility access",
                style = MaterialTheme.typography.headlineSmall,
                color = textColor
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "App Lock uses Android Accessibility Service to detect when a protected app is opened and present the App Lock authentication interface.\n\n" +
                    "This access is used for App Lock protection. App Lock does not intentionally collect or transmit private messages, passwords, photos, or other private screen content for this purpose.\n\n" +
                    "You can disable this access at any time from Android Settings.",
                color = secondaryText,
                style = MaterialTheme.typography.bodyLarge
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Checkbox(
                    checked = accepted,
                    onCheckedChange = { accepted = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = primary,
                        uncheckedColor = textColor,
                        checkmarkColor = Color.White
                    )
                )

                Text(
                    text = "I understand and agree to enable this access.",
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    app.repository.setDisclosureAccepted(true)

                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )

                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = accepted,
                colors = ButtonDefaults.buttonColors(
                    containerColor = primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text("Continue to Android Settings")
            }
        }
    }
}