package applock.app.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

@Composable
fun LockScreen(
    packageName: String,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val repo = app.repository

    val theme by repo.theme.collectAsState()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var forcePin by remember { mutableStateOf(false) }

    val alpha = remember {
        Animatable(
            if (theme.reducedMotion) 1f else 0f
        )
    }

    val scope = rememberCoroutineScope()

    val label = remember(packageName) {
        runCatching {
            context.packageManager
                .getApplicationLabel(
                    context.packageManager.getApplicationInfo(
                        packageName,
                        0
                    )
                )
                .toString()
        }.getOrDefault("Protected app")
    }

    LaunchedEffect(theme.reducedMotion) {
        if (!theme.reducedMotion) {
            alpha.animateTo(
                1f,
                tween(140)
            )
        }
    }

    fun success() {
        app.lockEngine.unlock(packageName)

        scope.launch {
            alpha.animateTo(
                1f,
                tween(60)
            )

            onSuccess()
        }
    }

    fun verifyPin() {
        if (pin.length !in 4..8) {
            error = true
            return
        }

        if (repo.verifyPin(pin)) {
            error = false
            success()
        } else {
            error = true
            pin = ""
        }
    }

    fun biometric() {
        val activity = context as? FragmentActivity ?: return

        val ready =
            BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            ) == BiometricManager.BIOMETRIC_SUCCESS

        if (!ready) {
            forcePin = true
            return
        }

        val executor = ContextCompat.getMainExecutor(context)

        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    success()
                }
            }
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock protected app")
                .setSubtitle("Use your device biometric")
                .setNegativeButtonText("Use PIN")
                .setConfirmationRequired(false)
                .build()
        )
    }

    val method = repo.getAuthMethod()

    val showBiometric =
        method == AuthMethod.BIOMETRIC && !forcePin

    val showPattern =
        method == AuthMethod.PATTERN && !forcePin

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha.value),
        color = MaterialTheme.colorScheme.background
    ) {

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                )
        ) {

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = 28.dp,
                        vertical = 32.dp
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {

                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    text = "Protected app",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(Modifier.height(5.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )

                    Spacer(Modifier.size(6.dp))

                    Text(
                        text = "AppLock security check",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(28.dp))

                when {

                    showBiometric -> {

                        Button(
                            onClick = {
                                biometric()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {

                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null
                            )

                            Spacer(Modifier.size(10.dp))

                            Text("Unlock with biometric")
                        }

                        Spacer(Modifier.height(10.dp))

                        if (repo.hasPin()) {
                            TextButton(
                                onClick = {
                                    forcePin = true
                                    error = false
                                }
                            ) {
                                Text("Use PIN instead")
                            }
                        }
                    }

                    showPattern -> {

                        Text(
                            text = "Draw your pattern",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(16.dp))

                        PatternGrid(
                            onComplete = { value ->
                                if (repo.verifyPattern(value)) {
                                    error = false
                                    success()
                                } else {
                                    error = true
                                }
                            }
                        )

                        if (error) {
                            Spacer(Modifier.height(8.dp))

                            Text(
                                text = "Incorrect pattern",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    else -> {

                        Text(
                            text = if (error) {
                                "Incorrect PIN — try again"
                            } else {
                                "Enter your PIN"
                            },
                            color = if (error) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )

                        Spacer(Modifier.height(14.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            repeat(8) { index ->

                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(
                                            color =
                                                if (index < pin.length) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                            shape = CircleShape
                                        )
                                )
                            }
                        }

                        Spacer(Modifier.height(22.dp))

                        val keys = listOf(
                            "1", "2", "3",
                            "4", "5", "6",
                            "7", "8", "9",
                            "⌫", "0", "UNLOCK"
                        )

                        keys.chunked(3).forEach { row ->

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {

                                row.forEach { key ->

                                    when (key) {

                                        "⌫" -> {
                                            Button(
                                                onClick = {
                                                    pin = pin.dropLast(1)
                                                    error = false
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(56.dp),
                                                shape = RoundedCornerShape(
                                                    theme.cornerRadius.dp
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Backspace,
                                                    contentDescription = "Delete"
                                                )
                                            }
                                        }

                                        "UNLOCK" -> {
                                            Button(
                                                onClick = {
                                                    verifyPin()
                                                },
                                                enabled = pin.length in 4..8,
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(56.dp),
                                                shape = RoundedCornerShape(
                                                    theme.cornerRadius.dp
                                                )
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null
                                                )

                                                Spacer(Modifier.size(5.dp))

                                                Text("Unlock")
                                            }
                                        }

                                        else -> {
                                            Button(
                                                onClick = {
                                                    if (pin.length < 8) {
                                                        pin += key
                                                        error = false
                                                    }
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(56.dp),
                                                shape = RoundedCornerShape(
                                                    theme.cornerRadius.dp
                                                )
                                            ) {
                                                Text(
                                                    text = key,
                                                    style = MaterialTheme.typography.titleLarge
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                        }

                        if (
                            method == AuthMethod.BIOMETRIC &&
                            repo.hasPin()
                        ) {
                            TextButton(
                                onClick = {
                                    forcePin = false
                                    error = false
                                }
                            ) {
                                Text("Use biometric")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternGrid(
    onComplete: (String) -> Unit
) {
    var selected by remember {
        mutableStateOf(emptyList<Int>())
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        repeat(3) { row ->

            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                repeat(3) { column ->

                    val index = row * 3 + column
                    val isSelected = selected.contains(index)

                    Button(
                        onClick = {
                            if (!selected.contains(index)) {

                                selected = selected + index

                                if (selected.size >= 4) {
                                    onComplete(
                                        selected.joinToString("-")
                                    )

                                    selected = emptyList()
                                }
                            }
                        },
                        modifier = Modifier.size(66.dp),
                        shape = CircleShape
                    ) {
                        Text(
                            text = if (isSelected) "●" else ""
                        )
                    }
                }
            }
        }
    }
}