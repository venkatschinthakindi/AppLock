package applock.app.ui.screens
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import applock.app.data.AppLockRepository
import applock.app.domain.AuthMethod
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun SecuritySetupScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val repo = app.repository

    var currentMethod by remember { mutableStateOf(repo.getAuthMethod()) }
    var alreadyConfigured by remember { mutableStateOf(repo.authenticationConfigured()) }

    var authorized by remember { mutableStateOf(!alreadyConfigured) }
    var usingPinFallback by remember { mutableStateOf(false) }

    var gatePin by remember { mutableStateOf("") }
    var gateError by remember { mutableStateOf("") }
    var gatePattern by remember { mutableStateOf(emptyList<Int>()) }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val latestMethod = repo.getAuthMethod()
                val latestConfigured = repo.authenticationConfigured()

                if (latestMethod != currentMethod) {
                    currentMethod = latestMethod
                    usingPinFallback = false
                    gatePin = ""
                    gatePattern = emptyList()
                    gateError = ""
                }

                if (latestConfigured != alreadyConfigured) {
                    alreadyConfigured = latestConfigured
                    if (!latestConfigured) {
                        authorized = true
                        gatePin = ""
                        gatePattern = emptyList()
                        gateError = ""
                    }
                }
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(alreadyConfigured, currentMethod) {
        if (
            alreadyConfigured &&
            currentMethod == AuthMethod.BIOMETRIC &&
            !usingPinFallback
        ) {
            launchBiometricGate(
                context = context,
                onSuccess = { authorized = true },
                onFallback = { usingPinFallback = true }
            )
        }
    }

    if (!authorized) {
        SecurityGateContent(
            currentMethod = currentMethod,
            usingPinFallback = usingPinFallback,
            gatePin = gatePin,
            gateError = gateError,
            gatePattern = gatePattern,
            repo = repo,
            onPinChange = {
                if (it.length <= 8 && it.all(Char::isDigit)) {
                    gatePin = it
                    gateError = ""
                }
            },
            onUnlock = {
                if (repo.verifyPin(gatePin)) {
                    authorized = true
                    gatePin = ""
                    gateError = ""
                } else {
                    gatePin = ""
                    gateError = "Incorrect PIN"
                }
            },
            onPatternChange = { completed ->
                if (completed.size >= 4) {
                    if (repo.verifyPattern(completed.joinToString("-"))) {
                        authorized = true
                        gatePattern = emptyList()
                        gateError = ""
                    } else {
                        gatePattern = emptyList()
                        gateError = "Incorrect pattern"
                    }
                } else {
                    gatePattern = emptyList()
                }
            },
            onUseBiometric = {
                usingPinFallback = false
                gatePin = ""
                gateError = ""

                launchBiometricGate(
                    context,
                    { authorized = true },
                    { usingPinFallback = true }
                )
            }
        )

        return
    }

    AuthenticationEditor(repo)
}

@Composable
private fun SecurityGateContent(
    currentMethod: AuthMethod,
    usingPinFallback: Boolean,
    gatePin: String,
    gateError: String,
    gatePattern: List<Int>,
    repo: AppLockRepository,
    onPinChange: (String) -> Unit,
    onUnlock: () -> Unit,
    onPatternChange: (List<Int>) -> Unit,
    onUseBiometric: () -> Unit
) {
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(
                                alpha = .55f
                            )
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(
                        horizontal = 20.dp,
                        vertical = 24.dp
                    )
                    .widthIn(max = 620.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .background(
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = .12f
                            ),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(38.dp)
                    )
                }

                Text(
                    "Security settings are protected",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    "Authenticate with your existing " +
                        "${currentMethod.displayName()} before changing PIN, " +
                        "pattern, biometric settings, or the authentication method.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )

                when {
                    currentMethod == AuthMethod.PIN || usingPinFallback -> {
                        OutlinedTextField(
                            value = gatePin,
                            onValueChange = onPinChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Current PIN") },
                            supportingText = {
                                Text(
                                    "Your existing PIN is never displayed or prefilled."
                                )
                            },
                            visualTransformation =
                                PasswordVisualTransformation(),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType =
                                        KeyboardType.NumberPassword
                                ),
                            singleLine = true
                        )

                        Button(
                            onClick = onUnlock,
                            enabled = gatePin.length in 4..8,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("Unlock security settings")
                        }

                        if (
                            currentMethod == AuthMethod.BIOMETRIC &&
                            repo.hasPin()
                        ) {
                            TextButton(
                                onClick = onUseBiometric
                            ) {
                                Text("Use biometric instead")
                            }
                        }
                    }

                    currentMethod == AuthMethod.PATTERN -> {
                        Text(
                            "Draw your existing pattern",
                            style = MaterialTheme.typography.titleMedium
                        )

                        PatternSetupGrid(
                            selected = gatePattern,
                            onChanged = onPatternChange
                        )
                    }
                }

                if (gateError.isNotEmpty()) {
                    Text(
                        gateError,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun AuthenticationEditor(
    repo: AppLockRepository
) {
    var method by remember { mutableStateOf(repo.getAuthMethod()) }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var pattern by remember { mutableStateOf(emptyList<Int>()) }
    var patternConfirm by remember { mutableStateOf(emptyList<Int>()) }
    var message by remember { mutableStateOf("") }

    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val biometricReady =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .imePadding()
            .navigationBarsPadding()
            .padding(
                horizontal = 20.dp,
                vertical = 20.dp
            )
            .widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.primaryContainer.copy(
                        alpha = .85f
                    )
            )
        ) {
            Column(Modifier.padding(22.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )

                    Spacer(Modifier.size(12.dp))

                    Column {
                        Text(
                            "Authentication",
                            style =
                                MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Protected configuration",
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "Changes are secured by your existing credential. " +
                        "New PINs and patterns are never shown back to you.",
                    color =
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            "Unlock method",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AuthMethod.entries.forEach { option ->
                FilterChip(
                    selected = method == option,
                    onClick = {
                        method = option
                        message = ""
                    },
                    label = {
                        Text(option.displayName())
                    },
                    leadingIcon = {
                        Icon(
                            when (option) {
                                AuthMethod.PIN ->
                                    Icons.Default.Lock

                                AuthMethod.PATTERN ->
                                    Icons.Default.Lock

                                AuthMethod.BIOMETRIC ->
                                    Icons.Default.Fingerprint
                            },
                            contentDescription = null
                        )
                    }
                )
            }
        }

        when (method) {
            AuthMethod.PIN -> {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Create a new PIN",
                            style =
                                MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            "4–8 digits. Input is masked and the old PIN is never revealed.",
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = pin,
                            onValueChange = {
                                if (
                                    it.length <= 8 &&
                                    it.all(Char::isDigit)
                                ) {
                                    pin = it
                                    message = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("New PIN") },
                            visualTransformation =
                                PasswordVisualTransformation(),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType =
                                        KeyboardType.NumberPassword
                                ),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = confirm,
                            onValueChange = {
                                if (
                                    it.length <= 8 &&
                                    it.all(Char::isDigit)
                                ) {
                                    confirm = it
                                    message = ""
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Confirm new PIN") },
                            visualTransformation =
                                PasswordVisualTransformation(),
                            keyboardOptions =
                                KeyboardOptions(
                                    keyboardType =
                                        KeyboardType.NumberPassword
                                ),
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
                            enabled =
                                pin.length in 4..8 &&
                                    pin == confirm,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Save new PIN")
                        }
                    }
                }
            }

            AuthMethod.PATTERN -> {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        horizontalAlignment =
                            Alignment.CenterHorizontally,
                        verticalArrangement =
                            Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Create a new pattern",
                            style =
                                MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            "Use at least 4 points. The saved pattern is never displayed.",
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        PatternSetupGrid(
                            selected = pattern,
                            onChanged = {
                                pattern = it
                            }
                        )

                        Row(
                            horizontalArrangement =
                                Arrangement.spacedBy(10.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    pattern = emptyList()
                                    patternConfirm = emptyList()
                                    message = ""
                                }
                            ) {
                                Text("Clear")
                            }

                            Button(
                                enabled = pattern.size >= 4,
                                onClick = {
                                    patternConfirm = pattern
                                    pattern = emptyList()
                                }
                            ) {
                                Text("Continue")
                            }
                        }

                        if (patternConfirm.isNotEmpty()) {
                            Text(
                                "Confirm the new pattern",
                                style =
                                    MaterialTheme.typography.labelLarge
                            )

                            PatternSetupGrid(
                                selected = emptyList(),
                                onChanged = { second ->
                                    if (second.size >= 4) {
                                        if (second == patternConfirm) {
                                            repo.setPattern(
                                                second.joinToString("-")
                                            )
                                            repo.setAuthMethod(
                                                AuthMethod.PATTERN
                                            )
                                            patternConfirm = emptyList()
                                            message =
                                                "Pattern updated securely"
                                        } else {
                                            patternConfirm = emptyList()
                                            message =
                                                "Patterns did not match"
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            AuthMethod.BIOMETRIC -> {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp)
                ) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement =
                            Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint =
                                    MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )

                            Spacer(Modifier.size(12.dp))

                            Text(
                                "Biometric unlock",
                                style =
                                    MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            if (biometricReady) {
                                "Your device reports biometric authentication is ready. " +
                                    "A PIN fallback is required for reliable recovery."
                            } else {
                                "Biometric authentication is not currently ready. " +
                                    "Set a PIN fallback first."
                            },
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!repo.hasPin()) {
                            OutlinedTextField(
                                value = pin,
                                onValueChange = {
                                    if (
                                        it.length <= 8 &&
                                        it.all(Char::isDigit)
                                    ) {
                                        pin = it
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Fallback PIN") },
                                visualTransformation =
                                    PasswordVisualTransformation(),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType =
                                            KeyboardType.NumberPassword
                                    ),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = confirm,
                                onValueChange = {
                                    if (
                                        it.length <= 8 &&
                                        it.all(Char::isDigit)
                                    ) {
                                        confirm = it
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text("Confirm fallback PIN")
                                },
                                visualTransformation =
                                    PasswordVisualTransformation(),
                                keyboardOptions =
                                    KeyboardOptions(
                                        keyboardType =
                                            KeyboardType.NumberPassword
                                    ),
                                singleLine = true
                            )
                        }

                        Button(
                            onClick = {
                                if (
                                    !repo.hasPin() &&
                                    pin.length in 4..8 &&
                                    pin == confirm
                                ) {
                                    repo.setPin(pin)
                                    pin = ""
                                    confirm = ""
                                }

                                if (
                                    repo.hasPin() &&
                                    biometricReady
                                ) {
                                    repo.setAuthMethod(
                                        AuthMethod.BIOMETRIC
                                    )
                                    message =
                                        "Biometric unlock enabled"
                                }
                            },
                            enabled =
                                biometricReady &&
                                    (
                                        repo.hasPin() ||
                                            (
                                                pin.length in 4..8 &&
                                                    pin == confirm
                                            )
                                        ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text("Enable biometric unlock")
                        }
                    }
                }
            }
        }

        if (message.isNotEmpty()) {
            Text(
                message,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

private fun launchBiometricGate(
    context: Context,
    onSuccess: () -> Unit,
    onFallback: () -> Unit
) {
    val activity =
        context as? FragmentActivity ?: run {
            onFallback()
            return
        }

    val ready =
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        ) == BiometricManager.BIOMETRIC_SUCCESS

    if (!ready) {
        onFallback()
        return
    }

    val executor =
        ContextCompat.getMainExecutor(context)

    BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(
                result: BiometricPrompt.AuthenticationResult
            ) {
                onSuccess()
            }

            override fun onAuthenticationError(
                errorCode: Int,
                errString: CharSequence
            ) {
                onFallback()
            }
        }
    ).authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Verify to change security settings")
            .setSubtitle("Confirm your existing biometric")
            .setNegativeButtonText("Use PIN")
            .setConfirmationRequired(false)
            .build()
    )
}

private fun AuthMethod.displayName(): String =
    when (this) {
        AuthMethod.PIN -> "PIN"
        AuthMethod.PATTERN -> "Pattern"
        AuthMethod.BIOMETRIC -> "Biometric"
    }

@Composable
private fun PatternSetupGrid(
    selected: List<Int>,
    onChanged: (List<Int>) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragSelection by remember { mutableStateOf(selected) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(selected) {
        if (!isDragging) {
            dragSelection = selected
        }
    }

    fun pointFor(
        index: Int,
        width: Float,
        height: Float
    ): Offset {
        val row = index / 3
        val column = index % 3

        return Offset(
            x = width * (0.20f + column * 0.30f),
            y = height * (0.20f + row * 0.30f)
        )
    }

    fun distanceBetween(first: Offset, second: Offset): Float {
        val dx = first.x - second.x
        val dy = first.y - second.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun hitTest(
        position: Offset,
        width: Float,
        height: Float
    ): Int? {
        /*
         * Keep the hit area generous enough for a finger while keeping
         * adjacent points distinguishable. The gesture surface itself is
         * larger than the visible dots, just like a native pattern lock.
         */
        val threshold = minOf(width, height) * 0.15f

        var bestIndex: Int? = null
        var bestDistance = Float.MAX_VALUE

        repeat(9) { index ->
            val point = pointFor(index, width, height)
            val distance = distanceBetween(position, point)

            if (distance <= threshold && distance < bestDistance) {
                bestIndex = index
                bestDistance = distance
            }
        }

        return bestIndex
    }

    fun appendPoint(
        current: List<Int>,
        index: Int
    ): List<Int> {
        if (current.contains(index)) {
            return current
        }

        val result = current.toMutableList()
        val previous = current.lastOrNull()

        if (previous != null) {
            val previousRow = previous / 3
            val previousColumn = previous % 3
            val row = index / 3
            val column = index % 3

            val rowDelta = row - previousRow
            val columnDelta = column - previousColumn

            /*
             * Android-style pattern behaviour: if a move jumps over the
             * centre point of a straight/diagonal two-cell segment, include
             * that point automatically.
             */
            if (
                (
                    kotlin.math.abs(rowDelta) == 2 &&
                        columnDelta == 0
                    ) ||
                    (
                        kotlin.math.abs(columnDelta) == 2 &&
                            rowDelta == 0
                        ) ||
                    (
                        kotlin.math.abs(rowDelta) == 2 &&
                            kotlin.math.abs(columnDelta) == 2
                        )
            ) {
                val middleRow = (previousRow + row) / 2
                val middleColumn = (previousColumn + column) / 2
                val middle = middleRow * 3 + middleColumn

                if (!result.contains(middle)) {
                    result.add(middle)
                }
            }
        }

        result.add(index)
        return result
    }

    fun finishGesture() {
        if (!isDragging) return

        isDragging = false
        dragPosition = null

        val completedPattern = dragSelection
        dragSelection = emptyList()

        if (completedPattern.size >= 4) {
            onChanged(completedPattern)
        } else {
            onChanged(emptyList())
        }
    }

    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val background = MaterialTheme.colorScheme.background

    /*
     * This grid deliberately handles the pointer stream at the Initial
     * pointer-event pass. Security setup lives inside a vertically scrolling
     * screen, and allowing the parent scroll container to win the drag can
     * make the pattern appear non-clickable/non-interactive. Consuming the
     * gesture here makes the entire 3x3 surface behave as one native-style
     * pattern gesture.
     */
    Canvas(
        modifier = Modifier
            .size(290.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                    )

                    down.consume()

                    val width = size.width.toFloat()
                    val height = size.height.toFloat()

                    val first = hitTest(
                        down.position,
                        width,
                        height
                    )

                    if (first == null) {
                        dragSelection = emptyList()
                        dragPosition = null
                        isDragging = false
                        return@awaitEachGesture
                    }

                    isDragging = true
                    dragSelection = appendPoint(emptyList(), first)
                    dragPosition = down.position

                    while (true) {
                        val event =
                            awaitPointerEvent(
                                androidx.compose.ui.input.pointer.PointerEventPass.Initial
                            )
                        val change = event.changes.firstOrNull()
                            ?: continue

                        if (change.pressed) {
                            change.consume()
                            dragPosition = change.position

                            val index = hitTest(
                                change.position,
                                width,
                                height
                            )

                            if (index != null) {
                                dragSelection =
                                    appendPoint(
                                        dragSelection,
                                        index
                                    )
                            }
                        } else {
                            change.consume()
                            finishGesture()
                            break
                        }
                    }
                }
            }
    ) {
        val nodeRadius = size.minDimension * 0.055f
        val selectedRadius = nodeRadius * 1.35f

        if (dragSelection.size >= 2) {
            dragSelection.zipWithNext().forEach { (from, to) ->
                drawLine(
                    color = primary,
                    start = pointFor(from, size.width, size.height),
                    end = pointFor(to, size.width, size.height),
                    strokeWidth = nodeRadius * 0.70f
                )
            }
        }

        if (isDragging && dragPosition != null && dragSelection.isNotEmpty()) {
            drawLine(
                color = primary.copy(alpha = 0.65f),
                start = pointFor(
                    dragSelection.last(),
                    size.width,
                    size.height
                ),
                end = dragPosition!!,
                strokeWidth = nodeRadius * 0.60f
            )
        }

        repeat(9) { index ->
            val point = pointFor(index, size.width, size.height)
            val isSelected = dragSelection.contains(index)

            drawCircle(
                color = if (isSelected) primary else inactive,
                radius = if (isSelected) selectedRadius else nodeRadius,
                center = point
            )

            if (isSelected) {
                drawCircle(
                    color = background,
                    radius = nodeRadius * 0.38f,
                    center = point
                )
            }
        }
    }
}

