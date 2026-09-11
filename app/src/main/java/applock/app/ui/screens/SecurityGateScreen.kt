package applock.app.ui.screens

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
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
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication
import applock.app.domain.AuthMethod

/**
 * Reusable authentication gate for security-sensitive AppLock configuration.
 * It never exposes or pre-fills the existing credential.
 */
@Composable
fun SecurityGateScreen(
    title: String,
    description: String,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val repo = app.repository
    val method = remember { repo.getAuthMethod() }
    val configured = remember { repo.authenticationConfigured() }
    var authorized by remember { mutableStateOf(!configured) }
    var usePinFallback by remember { mutableStateOf(method != AuthMethod.BIOMETRIC) }
    var pin by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf(emptyList<Int>()) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(configured, method) {
        if (configured && method == AuthMethod.BIOMETRIC && !usePinFallback) {
            launchSecurityBiometric(
                context = context,
                onSuccess = { authorized = true },
                onFallback = { usePinFallback = true }
            )
        }
    }

    if (authorized) {
        content()
        return
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
                    )
                )
            )
        ) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), CircleShape)
                        .padding(20.dp)
                ) {
                    Icon(Icons.Default.Security, null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(18.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(22.dp))

                when {
                    method == AuthMethod.PIN || usePinFallback -> {
                        OutlinedTextField(
                            value = pin,
                            onValueChange = {
                                if (it.length <= 8 && it.all(Char::isDigit)) {
                                    pin = it
                                    error = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Current PIN") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            singleLine = true
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                if (repo.verifyPin(pin)) {
                                    authorized = true
                                    pin = ""
                                } else {
                                    pin = ""
                                    error = "Incorrect PIN"
                                }
                            },
                            enabled = pin.length in 4..8,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp)
                        ) { Text("Unlock settings") }
                        if (method == AuthMethod.BIOMETRIC && repo.hasPin()) {
                            TextButton(onClick = {
                                usePinFallback = false
                                pin = ""
                                error = ""
                                launchSecurityBiometric(context, { authorized = true }, { usePinFallback = true })
                            }) { Text("Use biometric instead") }
                        }
                    }
                    method == AuthMethod.PATTERN -> {
                        Text("Draw your existing pattern", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(14.dp))
                        PatternGateGrid(pattern) { next ->
                            pattern = next
                            if (next.size >= 4) {
                                if (repo.verifyPattern(next.joinToString("-"))) {
                                    authorized = true
                                    pattern = emptyList()
                                } else {
                                    pattern = emptyList()
                                    error = "Incorrect pattern"
                                }
                            }
                        }
                    }
                }

                if (error.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun launchSecurityBiometric(
    context: Context,
    onSuccess: () -> Unit,
    onFallback: () -> Unit
) {
    val activity = context as? FragmentActivity ?: run {
        onFallback()
        return
    }
    val can = BiometricManager.from(context).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
    )
    if (can != BiometricManager.BIOMETRIC_SUCCESS) {
        onFallback()
        return
    }
    val executor = androidx.core.content.ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFallback()
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verify AppLock security")
            .setSubtitle("Authenticate to change protected settings")
            .setNegativeButtonText("Use PIN")
            .build()
    )
}

@Composable
private fun PatternGateGrid(selected: List<Int>, onComplete: (List<Int>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        repeat(3) { row ->
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { col ->
                    val index = row * 3 + col
                    Button(
                        onClick = {
                            if (!selected.contains(index)) {
                                val next = selected + index
                                if (next.size >= 4) onComplete(next)
                                else onCompletePartial(next, onComplete)
                            }
                        },
                        modifier = Modifier.padding(2.dp).height(56.dp).fillMaxWidth(.28f),
                        shape = CircleShape
                    ) {
                        Text(if (selected.contains(index)) "●" else "")
                    }
                }
            }
        }
    }
}

private fun onCompletePartial(next: List<Int>, callback: (List<Int>) -> Unit) = callback(next)
