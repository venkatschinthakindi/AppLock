package applock.app.ui.screens

import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import applock.app.AppLockApplication
import applock.app.domain.AuthMethod

@Composable
fun SecuritySetupScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    var method by remember { mutableStateOf(app.repository.getAuthMethod()) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf(emptyList<Int>()) }
    var patternConfirm by remember { mutableStateOf(emptyList<Int>()) }
    val biometricReady = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Authentication", style = MaterialTheme.typography.headlineSmall)
        Text("Configure the credential used to unlock protected apps. Free and Pro use the same security path.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthMethod.entries.forEach { option ->
                FilterChip(selected = method == option, onClick = { method = option; app.repository.setAuthMethod(option) }, label = { Text(option.displayName()) })
            }
        }

        when (method) {
            AuthMethod.PIN -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("PIN", style = MaterialTheme.typography.titleLarge)
                        Text("Use 4–8 digits. The PIN is stored encrypted with Android Keystore.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(value = pin, onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it }, label = { Text("PIN") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = confirm, onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) confirm = it }, label = { Text("Confirm PIN") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = pin.length in 4..8 && pin == confirm, onClick = { app.repository.setPin(pin) }) { Text("Save PIN") }
                    }
                }
            }
            AuthMethod.PATTERN -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Pattern", style = MaterialTheme.typography.titleLarge)
                        Text("Choose at least 4 points, then confirm the same pattern.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PatternSetupGrid(pattern) { pattern = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = { pattern = emptyList(); patternConfirm = emptyList() }) { Text("Clear") }
                            Button(enabled = pattern.size >= 4, onClick = { patternConfirm = pattern }) { Text("Continue") }
                        }
                        if (patternConfirm.isNotEmpty()) {
                            Text("Draw the same pattern again.", style = MaterialTheme.typography.labelLarge)
                            PatternSetupGrid(emptyList()) { second ->
                                if (second == patternConfirm) {
                                    app.repository.setPattern(second.joinToString("-"))
                                    pattern = emptyList()
                                    patternConfirm = emptyList()
                                }
                            }
                        }
                    }
                }
            }
            AuthMethod.BIOMETRIC -> {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Biometric unlock", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (biometricReady) "Android reports an enrolled biometric method ready for BiometricPrompt." else "Biometric authentication is not currently ready on this device. Set a PIN fallback before protecting apps.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!app.repository.hasPin()) {
                            Text("Fallback PIN", style = MaterialTheme.typography.titleMedium)
                            OutlinedTextField(value = pin, onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it }, label = { Text("4–8 digit PIN") }, modifier = Modifier.fillMaxWidth())
                            Button(enabled = pin.length in 4..8, onClick = { app.repository.setPin(pin) }) { Text("Save fallback PIN") }
                        }
                    }
                }
            }
        }
    }
}

private fun AuthMethod.displayName() = when (this) {
    AuthMethod.PIN -> "PIN"
    AuthMethod.PATTERN -> "Pattern"
    AuthMethod.BIOMETRIC -> "Biometric"
}

@Composable
private fun PatternSetupGrid(selected: List<Int>, onChanged: (List<Int>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(3) { r ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { c ->
                    val i = r * 3 + c
                    Button(
                        onClick = { if (!selected.contains(i)) onChanged(selected + i) },
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape
                    ) { Text(if (selected.contains(i)) "●" else "") }
                }
            }
        }
    }
}
