package applock.app.ui.screens

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication
import applock.app.domain.AuthMethod

@Composable
fun SecuritySetupScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val repo = app.repository
    val currentMethod = remember { repo.getAuthMethod() }
    val alreadyConfigured = remember { repo.hasCredential() }

    var authorized by remember { mutableStateOf(!alreadyConfigured) }
    var usingPinFallback by remember { mutableStateOf(currentMethod != AuthMethod.BIOMETRIC) }
    var gatePin by remember { mutableStateOf("") }
    var gateError by remember { mutableStateOf("") }
    var gatePattern by remember { mutableStateOf(emptyList<Int>()) }

    LaunchedEffect(alreadyConfigured, currentMethod) {
        if (alreadyConfigured && currentMethod == AuthMethod.BIOMETRIC && !usingPinFallback) {
            launchBiometricGate(
                context = context,
                onSuccess = { authorized = true },
                onFallback = { usingPinFallback = true }
            )
        }
    }

    if (!authorized) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
                            )
                        )
                    )
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .size(84.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Security settings are protected", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Authenticate with your existing ${currentMethod.displayName()} before changing PIN, pattern, biometric settings, or the authentication method.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(Modifier.height(22.dp))

                    when {
                        currentMethod == AuthMethod.PIN || usingPinFallback -> {
                            OutlinedTextField(
                                value = gatePin,
                                onValueChange = {
                                    if (it.length <= 8 && it.all(Char::isDigit)) {
                                        gatePin = it
                                        gateError = ""
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Current PIN") },
                                supportingText = { Text("Your existing PIN is never displayed or prefilled.") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    if (repo.verifyPin(gatePin)) {
                                        authorized = true
                                        gatePin = ""
                                        gateError = ""
                                    } else {
                                        gatePin = ""
                                        gateError = "Incorrect PIN"
                                    }
                                },
                                enabled = gatePin.length in 4..8,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp)
                            ) { Text("Unlock security settings") }
                            if (currentMethod == AuthMethod.BIOMETRIC && repo.hasPin()) {
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = {
                                    usingPinFallback = false
                                    gatePin = ""
                                    gateError = ""
                                    launchBiometricGate(context, { authorized = true }, { usingPinFallback = true })
                                }) { Text("Use biometric instead") }
                            }
                        }
                        currentMethod == AuthMethod.PATTERN -> {
                            Text("Draw your existing pattern", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(14.dp))
                            PatternSetupGrid(gatePattern) { next ->
                                gatePattern = next
                                if (next.size >= 4) {
                                    if (repo.verifyPattern(next.joinToString("-"))) {
                                        authorized = true
                                        gatePattern = emptyList()
                                    } else {
                                        gatePattern = emptyList()
                                        gateError = "Incorrect pattern"
                                    }
                                }
                            }
                        }
                    }

                    if (gateError.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(gateError, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        return
    }

    AuthenticationEditor(repo = repo)
}

@Composable
private fun AuthenticationEditor(repo: applock.app.data.AppLockRepository) {
    var method by remember { mutableStateOf(repo.getAuthMethod()) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf(emptyList<Int>()) }
    var patternConfirm by remember { mutableStateOf(emptyList<Int>()) }
    var message by remember { mutableStateOf("") }
    val context = LocalContext.current
    val biometricReady = BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .85f))
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text("Authentication", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Protected configuration", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Changes are secured by your existing credential. New PINs and patterns are never shown back to you.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text("Unlock method", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AuthMethod.entries.forEach { option ->
                FilterChip(
                    selected = method == option,
                    onClick = { method = option; message = "" },
                    label = { Text(option.displayName()) },
                    leadingIcon = {
                        Icon(
                            when (option) {
                                AuthMethod.PIN -> Icons.Default.Lock
                                AuthMethod.PATTERN -> Icons.Default.Lock
                                AuthMethod.BIOMETRIC -> Icons.Default.Fingerprint
                            },
                            null
                        )
                    }
                )
            }
        }

        when (method) {
            AuthMethod.PIN -> {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Create a new PIN", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("4–8 digits. Input is masked and the old PIN is never revealed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { pin = it; message = "" } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("New PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = confirm,
                            onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) { confirm = it; message = "" } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Confirm new PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true
                        )
                        Button(
                            onClick = {
                                repo.setPin(pin)
                                repo.setAuthMethod(AuthMethod.PIN)
                                pin = ""
                                confirm = ""
                                message = "PIN updated securely"
                            },
                            enabled = pin.length in 4..8 && pin == confirm,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("Save new PIN") }
                    }
                }
            }
            AuthMethod.PATTERN -> {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Create a new pattern", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Use at least 4 points. The saved pattern is never displayed.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PatternSetupGrid(pattern) { pattern = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TextButton(onClick = { pattern = emptyList(); patternConfirm = emptyList(); message = "" }) { Text("Clear") }
                            Button(enabled = pattern.size >= 4, onClick = { patternConfirm = pattern; pattern = emptyList() }) { Text("Continue") }
                        }
                        if (patternConfirm.isNotEmpty()) {
                            Text("Confirm the new pattern", style = MaterialTheme.typography.labelLarge)
                            PatternSetupGrid(emptyList()) { second ->
                                if (second.size >= 4) {
                                    if (second == patternConfirm) {
                                        repo.setPattern(second.joinToString("-"))
                                        repo.setAuthMethod(AuthMethod.PATTERN)
                                        patternConfirm = emptyList()
                                        message = "Pattern updated securely"
                                    } else {
                                        patternConfirm = emptyList()
                                        message = "Patterns did not match"
                                    }
                                }
                            }
                        }
                    }
                }
            }
            AuthMethod.BIOMETRIC -> {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Fingerprint, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.size(12.dp))
                            Text("Biometric unlock", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                        Text(
                            if (biometricReady) "Your device reports biometric authentication is ready. A PIN fallback is required for reliable recovery." else "Biometric authentication is not currently ready. Set a PIN fallback first.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (!repo.hasPin()) {
                            OutlinedTextField(
                                value = pin,
                                onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) pin = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Fallback PIN") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = confirm,
                                onValueChange = { if (it.length <= 8 && it.all(Char::isDigit)) confirm = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Confirm fallback PIN") },
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                singleLine = true
                            )
                        }
                        Button(
                            onClick = {
                                if (!repo.hasPin() && pin.length in 4..8 && pin == confirm) {
                                    repo.setPin(pin)
                                    pin = ""
                                    confirm = ""
                                }
                                if (repo.hasPin() && biometricReady) {
                                    repo.setAuthMethod(AuthMethod.BIOMETRIC)
                                    message = "Biometric unlock enabled"
                                }
                            },
                            enabled = biometricReady && (repo.hasPin() || (pin.length in 4..8 && pin == confirm)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("Enable biometric unlock") }
                    }
                }
            }
        }

        if (message.isNotEmpty()) {
            Text(message, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun launchBiometricGate(
    context: Context,
    onSuccess: () -> Unit,
    onFallback: () -> Unit
) {
    val activity = context as? FragmentActivity ?: run { onFallback(); return }
    val ready = BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
    ) == BiometricManager.BIOMETRIC_SUCCESS
    if (!ready) { onFallback(); return }
    val executor = ContextCompat.getMainExecutor(context)
    BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFallback()
    }).authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verify to change security settings")
            .setSubtitle("Confirm your existing biometric")
            .setNegativeButtonText("Use PIN")
            .setConfirmationRequired(false)
            .build()
    )
}

private fun AuthMethod.displayName(): String = when (this) {
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
