package applock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication
import applock.app.domain.AuthMethod

@Composable
fun SecuritySetupScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    var method by remember { mutableStateOf(app.repository.getAuthMethod()) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf(emptyList<Int>()) }
    var patternConfirm by remember { mutableStateOf(emptyList<Int>()) }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Authentication", style = MaterialTheme.typography.headlineSmall)
        Text("Security behavior is identical for Free and Pro.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        AuthMethod.entries.forEach { FilterChip(selected = method == it, onClick = { method = it; app.repository.setAuthMethod(it) }, label = { Text(it.name) }) }
        when (method) {
            AuthMethod.PIN -> {
                OutlinedTextField(pin, { if (it.length <= 8 && it.all(Char::isDigit)) pin = it }, label = { Text("PIN (4–8 digits)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(confirm, { if (it.length <= 8 && it.all(Char::isDigit)) confirm = it }, label = { Text("Confirm PIN") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = pin.length in 4..8 && pin == confirm, onClick = { app.repository.setPin(pin) }) { Text("Save PIN") }
            }
            AuthMethod.PATTERN -> {
                Text("Draw a pattern of at least 4 points.")
                PatternSetupGrid(pattern) { pattern = it }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { pattern = emptyList() }) { Text("Clear") }
                    Button(enabled = pattern.size >= 4, onClick = { patternConfirm = pattern }) { Text("Use this pattern") }
                }
                if (patternConfirm.isNotEmpty()) {
                    Text("Draw it again to confirm.")
                    PatternSetupGrid(emptyList()) { second -> if (second == patternConfirm) { app.repository.setPattern(second.joinToString("-")); patternConfirm = emptyList(); pattern = emptyList() } }
                }
            }
            AuthMethod.BIOMETRIC -> Text("Biometric unlock uses Android BiometricPrompt. If biometric authentication is unavailable, configure a PIN fallback.")
        }
    }
}

@Composable
private fun PatternSetupGrid(selected: List<Int>, onChanged: (List<Int>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        repeat(3) { r -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { repeat(3) { c -> val i = r * 3 + c; Button(onClick = { if (!selected.contains(i)) onChanged(selected + i) }, modifier = Modifier.size(64.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp)) { Text(if (selected.contains(i)) "•" else "") } } } }
    }
}
