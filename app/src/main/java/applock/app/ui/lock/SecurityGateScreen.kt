package applock.app.ui.lock

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication
import applock.app.domain.AuthMethod
import applock.app.engine.LockEngine
import applock.app.security.SecurityAuthenticator
import kotlinx.coroutines.delay

@Composable
fun SecurityGateScreen(onAuthenticated: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val repo = app.repository
    val theme by repo.theme.collectAsState()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var forcePin by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf(emptyList<Int>()) }
    var lockoutRemaining by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (SecurityAuthenticator.isBlocked()) {
            lockoutRemaining = SecurityAuthenticator.remainingSeconds()
            delay(1_000L)
        }
        lockoutRemaining = 0
    }

    fun showResult(result: LockEngine.AuthenticationResult) {
        when (result) {
            LockEngine.AuthenticationResult.SUCCESS -> {
                error = ""
                onAuthenticated()
            }
            LockEngine.AuthenticationResult.BLOCKED -> {
                lockoutRemaining = SecurityAuthenticator.remainingSeconds()
                error = "Too many failed attempts"
            }
            LockEngine.AuthenticationResult.INVALID_CREDENTIAL -> {
                pin = ""
                error = "Incorrect credential"
            }
            else -> error = "Security authentication is not available"
        }
    }

    fun authenticatePin() {
        if (SecurityAuthenticator.isBlocked()) return
        showResult(SecurityAuthenticator.authenticatePin(app, pin))
    }

    fun authenticatePattern(value: String) {
        if (SecurityAuthenticator.isBlocked()) return
        showResult(SecurityAuthenticator.authenticatePattern(app, value))
    }

    fun authenticateBiometric() {
        val activity = context as? FragmentActivity ?: run {
            forcePin = true
            return
        }
        val ready = BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS
        if (!ready) {
            forcePin = true
            return
        }
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                showResult(SecurityAuthenticator.authenticateBiometric(app))
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) error = errString.toString()
            }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("App Lock security check")
                .setSubtitle("Authenticate before accessing device management controls")
                .setNegativeButtonText("Use PIN")
                .setConfirmationRequired(false)
                .build()
        )
    }

    val method = repo.getAuthMethod()
    val showBiometric = method == AuthMethod.BIOMETRIC && !forcePin
    val showPattern = method == AuthMethod.PATTERN && !forcePin
    val blocked = SecurityAuthenticator.isBlocked()

    Surface(
        modifier = Modifier.fillMaxSize().alpha(1f),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surfaceVariant)
                )
            )
        ) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.size(92.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .13f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
                }
                Spacer(Modifier.height(18.dp))
                Text("App Lock protection", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Security-sensitive system controls", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Authentication required", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(28.dp))

                if (blocked) {
                    Text("Too many attempts. Try again in ${lockoutRemaining}s.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                } else if (showBiometric) {
                    Button(
                        onClick = ::authenticateBiometric,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(theme.cornerRadius.dp)
                    ) {
                        Icon(Icons.Default.Fingerprint, contentDescription = null)
                        Spacer(Modifier.size(10.dp))
                        Text("Authenticate with biometric")
                    }
                    if (repo.hasPin()) {
                        TextButton(onClick = { forcePin = true; error = "" }) { Text("Use PIN instead") }
                    }
                } else if (showPattern) {
                    Text("Draw your pattern", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    repeat(3) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            repeat(3) { column ->
                                val index = row * 3 + column
                                val selected = pattern.contains(index)
                                Button(
                                    onClick = {
                                        if (!selected) {
                                            val next = pattern + index
                                            pattern = if (next.size >= 4) {
                                                authenticatePattern(next.joinToString("-"))
                                                emptyList()
                                            } else next
                                        }
                                    },
                                    modifier = Modifier.size(66.dp),
                                    shape = CircleShape
                                ) { Text(if (selected) "●" else "") }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }
                } else {
                    Text(if (error.isEmpty()) "Enter your PIN" else error, color = if (error.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(8) { index ->
                            Box(Modifier.size(12.dp).background(if (index < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape))
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "AUTH").chunked(3).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { key ->
                                when (key) {
                                    "⌫" -> Button(onClick = { pin = pin.dropLast(1); error = "" }, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(theme.cornerRadius.dp)) { Icon(Icons.Default.Backspace, contentDescription = "Delete") }
                                    "AUTH" -> Button(onClick = ::authenticatePin, enabled = pin.length in 4..8, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(theme.cornerRadius.dp)) { Icon(Icons.Default.Check, contentDescription = null); Spacer(Modifier.size(5.dp)); Text("Authenticate") }
                                    else -> Button(onClick = { if (pin.length < 8) { pin += key; error = "" } }, modifier = Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(theme.cornerRadius.dp)) { Text(key, style = MaterialTheme.typography.titleLarge) }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if (method == AuthMethod.BIOMETRIC && repo.hasPin()) {
                        TextButton(onClick = { forcePin = false; error = "" }) { Text("Use biometric") }
                    }
                }
            }
        }
    }
}
