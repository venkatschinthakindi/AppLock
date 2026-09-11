package applock.app.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import applock.app.AppLockApplication
import applock.app.domain.AuthMethod
import kotlinx.coroutines.launch

@Composable
fun LockScreen(packageName: String, onSuccess: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val repo = app.repository
    val theme by repo.theme.collectAsState()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { alpha.animateTo(1f, tween(140)) }

    fun success() {
        app.lockEngine.unlock(packageName)
        scope.launch { alpha.animateTo(1f, tween(80)); onSuccess() }
    }

    fun biometric() {
        val activity = context as? androidx.fragment.app.FragmentActivity ?: return
        val executor = ContextCompat.getMainExecutor(context)
        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = success()
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock protected app")
            .setSubtitle("Use your device biometric")
            .setNegativeButtonText("Use PIN")
            .setConfirmationRequired(false)
            .build())
    }

    Surface(modifier = Modifier.fillMaxSize().alpha(alpha.value), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .15f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
            }
            Spacer(Modifier.height(22.dp))
            Text("App Locked", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Unlock to continue", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(30.dp))
            when (repo.getAuthMethod()) {
                AuthMethod.BIOMETRIC -> {
                    Button(onClick = { biometric() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(10.dp)); Text("Unlock with biometric")
                    }
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { repo.setAuthMethod(AuthMethod.PIN) }) { Text("Use PIN instead") }
                }
                AuthMethod.PIN -> {
                    Text(if (error) "Incorrect PIN" else "Enter your PIN", color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(8) { i -> Box(Modifier.size(14.dp).background(if (i < pin.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape)) } }
                    Spacer(Modifier.height(24.dp))
                    val keys = listOf("1","2","3","4","5","6","7","8","9","","0","⌫")
                    keys.chunked(3).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { row.forEach { key -> Button(onClick = { if (key == "⌫") pin = pin.dropLast(1) else if (key.isNotEmpty() && pin.length < 8) pin += key; error = false; if (pin.length >= 4 && repo.verifyPin(pin)) success() }, enabled = key.isNotEmpty(), modifier = Modifier.weight(1f).height(58.dp), shape = RoundedCornerShape(theme.cornerRadius.dp)) { Text(if (key == "⌫") "Delete" else key) } } }; Spacer(Modifier.height(10.dp)) }
                }
                AuthMethod.PATTERN -> {
                    Text("Pattern unlock", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    PatternGrid(onComplete = { value -> if (repo.verifyPattern(value)) success() else error = true })
                    if (error) Text("Incorrect pattern", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PatternGrid(onComplete: (String) -> Unit) {
    var selected by remember { mutableStateOf(listOf<Int>()) }
    Column(verticalArrangement = Arrangement.spacedBy(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        (0 until 3).forEach { r -> Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) { (0 until 3).forEach { c -> val i = r*3+c; Button(onClick = { if (!selected.contains(i)) { val next = selected + i; selected = next; if (next.size >= 4) { onComplete(next.joinToString("-")); selected = emptyList() } } }, modifier = Modifier.size(70.dp), shape = CircleShape, contentPadding = PaddingValues(0.dp)) { Text(if (selected.contains(i)) "•" else "") } } } }
    }
}
