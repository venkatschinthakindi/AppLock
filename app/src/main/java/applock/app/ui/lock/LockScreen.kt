package applock.app.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import applock.app.ads.BannerAd
import applock.app.domain.AuthMethod
import applock.app.engine.LockEngine
import kotlinx.coroutines.delay

@Composable
fun LockScreen(
    packageName: String,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val repo = app.repository
    val theme by repo.theme.collectAsState()
    val engineState by app.lockEngine.state.collectAsState()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var forcePin by remember { mutableStateOf(false) }
    var lockoutRemaining by remember { mutableStateOf(0) }

    val label = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(
                    packageName,
                    0
                )
            ).toString()
        }.getOrDefault("Protected app")
    }

    LaunchedEffect(engineState) {
        while (app.lockEngine.isBlocked()) {
            lockoutRemaining =
                app.lockEngine.remainingLockoutSeconds()
            delay(1_000L)
        }

        lockoutRemaining = 0

        if (
            engineState ==
            LockEngine.State.TEMPORARILY_BLOCKED
        ) {
            error = ""
        }
    }

    fun showResult(
        result: LockEngine.AuthenticationResult
    ) {
        when (result) {
            LockEngine.AuthenticationResult.SUCCESS -> {
                error = ""
                pin = ""
                onSuccess()
            }

            LockEngine.AuthenticationResult.BLOCKED -> {
                lockoutRemaining =
                    app.lockEngine.remainingLockoutSeconds()
                error = "Too many failed attempts"
            }

            LockEngine.AuthenticationResult.INVALID_CREDENTIAL -> {
                pin = ""
                error = "Incorrect credential"
            }

            LockEngine.AuthenticationResult.NOT_PROTECTED,
            LockEngine.AuthenticationResult.NOT_CONFIGURED,
            LockEngine.AuthenticationResult.LIMITED_PROTECTION -> {
                error =
                    "Protection is not currently available"
            }
        }
    }

    fun authenticatePin() {
        if (app.lockEngine.isBlocked()) {
            lockoutRemaining =
                app.lockEngine.remainingLockoutSeconds()
            return
        }

        showResult(
            app.lockEngine.authenticatePin(
                packageName,
                pin
            )
        )
    }

    fun authenticatePattern(value: String) {
        if (app.lockEngine.isBlocked()) {
            lockoutRemaining =
                app.lockEngine.remainingLockoutSeconds()
            return
        }

        showResult(
            app.lockEngine.authenticatePattern(
                packageName,
                value
            )
        )
    }

    fun authenticateBiometric() {
        val activity =
            context as? FragmentActivity ?: run {
                forcePin = true
                return
            }

        val ready =
            BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            ) == BiometricManager.BIOMETRIC_SUCCESS

        if (!ready) {
            forcePin = true
            return
        }

        val executor =
            ContextCompat.getMainExecutor(context)

        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    showResult(
                        app.lockEngine
                            .completeBiometricAuthentication(
                                packageName
                            )
                    )
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    if (
                        errorCode !=
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        error = errString.toString()
                    }
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

    val blocked =
        app.lockEngine.isBlocked()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .alpha(1f),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {

            /*
             * SECURITY-CRITICAL AREA
             *
             * This area remains completely independent of:
             * - AdMob
             * - UMP
             * - network
             * - billing
             * - sponsor content
             */
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    )
            ) {

                val scrollState =
                    rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(
                            horizontal = 22.dp,
                            vertical = 24.dp
                        ),
                    horizontalAlignment =
                        Alignment.CenterHorizontally,
                    verticalArrangement =
                        Arrangement.Center
                ) {

                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(
                                    alpha = .13f
                                ),
                                CircleShape
                            ),
                        contentAlignment =
                            Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint =
                                MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier.size(40.dp)
                        )
                    }

                    androidx.compose.foundation.layout.Spacer(
                        Modifier.height(16.dp)
                    )

                    Text(
                        "Protected app",
                        style =
                            MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        label,
                        style =
                            MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    androidx.compose.foundation.layout.Spacer(
                        Modifier.height(6.dp)
                    )

                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint =
                                MaterialTheme.colorScheme.primary,
                            modifier =
                                Modifier.size(18.dp)
                        )

                        androidx.compose.foundation.layout.Spacer(
                            Modifier.size(6.dp)
                        )

                        Text(
                            "AppLock security check",
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .onSurfaceVariant
                        )
                    }

                    androidx.compose.foundation.layout.Spacer(
                        Modifier.height(24.dp)
                    )

                    when {

                        blocked -> {
                            Text(
                                if (lockoutRemaining > 0) {
                                    "Too many attempts. " +
                                        "Try again in " +
                                        "${lockoutRemaining}s."
                                } else {
                                    "Temporarily locked. " +
                                        "Try again shortly."
                                },
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .error,
                                fontWeight =
                                    FontWeight.SemiBold
                            )
                        }

                        showBiometric -> {

                            Button(
                                onClick =
                                    ::authenticateBiometric,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape =
                                    RoundedCornerShape(
                                        theme.cornerRadius.dp
                                    )
                            ) {
                                Icon(
                                    Icons.Default.Fingerprint,
                                    contentDescription = null
                                )

                                androidx.compose.foundation.layout.Spacer(
                                    Modifier.size(10.dp)
                                )

                                Text(
                                    "Unlock with biometric"
                                )
                            }

                            if (repo.hasPin()) {
                                TextButton(
                                    onClick = {
                                        forcePin = true
                                        error = ""
                                    }
                                ) {
                                    Text("Use PIN instead")
                                }
                            }
                        }

                        showPattern -> {

                            Text(
                                "Draw your pattern",
                                color =
                                    MaterialTheme
                                        .colorScheme
                                        .onSurfaceVariant
                            )

                            androidx.compose.foundation.layout.Spacer(
                                Modifier.height(14.dp)
                            )

                            PatternGrid(
                                onComplete =
                                    ::authenticatePattern
                            )
                        }

                        else -> {

                            Text(
                                if (error.isEmpty()) {
                                    "Enter your PIN"
                                } else {
                                    error
                                },
                                color =
                                    if (error.isEmpty()) {
                                        MaterialTheme
                                            .colorScheme
                                            .onSurfaceVariant
                                    } else {
                                        MaterialTheme
                                            .colorScheme
                                            .error
                                    }
                            )

                            androidx.compose.foundation.layout.Spacer(
                                Modifier.height(12.dp)
                            )

                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(9.dp)
                            ) {
                                repeat(8) { index ->
                                    Box(
                                        Modifier
                                            .size(11.dp)
                                            .background(
                                                if (
                                                    index < pin.length
                                                ) {
                                                    MaterialTheme
                                                        .colorScheme
                                                        .primary
                                                } else {
                                                    MaterialTheme
                                                        .colorScheme
                                                        .surfaceVariant
                                                },
                                                CircleShape
                                            )
                                    )
                                }
                            }

                            androidx.compose.foundation.layout.Spacer(
                                Modifier.height(20.dp)
                            )

                            val keys = listOf(
                                "1", "2", "3",
                                "4", "5", "6",
                                "7", "8", "9",
                                "⌫", "0", "UNLOCK"
                            )

                            keys.chunked(3).forEach { row ->

                                Row(
                                    modifier =
                                        Modifier.fillMaxWidth(),
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp)
                                ) {

                                    row.forEach { key ->

                                        when (key) {

                                            "⌫" -> {
                                                Button(
                                                    onClick = {
                                                        pin =
                                                            pin.dropLast(1)
                                                        error = ""
                                                    },
                                                    modifier =
                                                        Modifier
                                                            .weight(1f)
                                                            .height(56.dp),
                                                    shape =
                                                        RoundedCornerShape(
                                                            theme.cornerRadius.dp
                                                        )
                                                ) {
                                                    Icon(
                                                        Icons.Default.Backspace,
                                                        contentDescription =
                                                            "Delete"
                                                    )
                                                }
                                            }

                                            "UNLOCK" -> {
                                                Button(
                                                    onClick =
                                                        ::authenticatePin,
                                                    enabled =
                                                        pin.length in 4..8,
                                                    modifier =
                                                        Modifier
                                                            .weight(1f)
                                                            .height(56.dp),
                                                    shape =
                                                        RoundedCornerShape(
                                                            theme.cornerRadius.dp
                                                        )
                                                ) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription =
                                                            null
                                                    )

                                                    androidx.compose.foundation.layout.Spacer(
                                                        Modifier.size(5.dp)
                                                    )

                                                    Text("Unlock")
                                                }
                                            }

                                            else -> {
                                                Button(
                                                    onClick = {
                                                        if (
                                                            pin.length < 8
                                                        ) {
                                                            pin += key
                                                            error = ""
                                                        }
                                                    },
                                                    modifier =
                                                        Modifier
                                                            .weight(1f)
                                                            .height(56.dp),
                                                    shape =
                                                        RoundedCornerShape(
                                                            theme.cornerRadius.dp
                                                        )
                                                ) {
                                                    Text(
                                                        key,
                                                        style =
                                                            MaterialTheme
                                                                .typography
                                                                .titleLarge
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                androidx.compose.foundation.layout.Spacer(
                                    Modifier.height(8.dp)
                                )
                            }

                            if (
                                method == AuthMethod.BIOMETRIC &&
                                repo.hasPin()
                            ) {
                                TextButton(
                                    onClick = {
                                        forcePin = false
                                        error = ""
                                    }
                                ) {
                                    Text("Use biometric")
                                }
                            }
                        }
                    }

                    if (
                        error.isNotEmpty() &&
                        !blocked
                    ) {
                        androidx.compose.foundation.layout.Spacer(
                            Modifier.height(8.dp)
                        )

                        Text(
                            error,
                            color =
                                MaterialTheme
                                    .colorScheme
                                    .error
                        )
                    }

                    androidx.compose.foundation.layout.Spacer(
                        Modifier.height(12.dp)
                    )
                }
            }

            /*
             * NON-BLOCKING REVENUE AREA.
             *
             * Authentication has already been completely
             * separated from this area.
             */
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                BannerAd.Content(
                    enabled =
                        app.isAdsInitialized(),
                    modifier =
                        Modifier.fillMaxWidth()
                )
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
        verticalArrangement =
            Arrangement.spacedBy(10.dp),
        horizontalAlignment =
            Alignment.CenterHorizontally
    ) {
        repeat(3) { row ->
            Row(
                horizontalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {
                repeat(3) { column ->

                    val index =
                        row * 3 + column

                    val isSelected =
                        selected.contains(index)

                    Button(
                        onClick = {
                            if (!isSelected) {
                                val next =
                                    selected + index

                                selected = next

                                if (next.size >= 4) {
                                    onComplete(
                                        next.joinToString("-")
                                    )
                                    selected = emptyList()
                                }
                            }
                        },
                        modifier = Modifier.size(62.dp),
                        shape = CircleShape
                    ) {
                        Text(
                            if (isSelected) {
                                "●"
                            } else {
                                ""
                            }
                        )
                    }
                }
            }
        }
    }
}