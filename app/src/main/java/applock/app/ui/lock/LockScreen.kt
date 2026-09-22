package applock.app.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication
import applock.app.domain.AuthMethod
import applock.app.engine.LockEngine
import kotlinx.coroutines.delay
import java.security.SecureRandom
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Authentication UI for one exact protected package.
 *
 * Security rules:
 * - No ads, billing, network or sponsor content are rendered here.
 * - Authentication is accepted only by the request-bound LockEngine.
 * - The pattern input is a real drag gesture, not a sequence of unrelated taps.
 */
@Composable
fun LockScreen(
    packageName: String,
    onSuccess: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val repo = app.repository

    var pin by remember(packageName) { mutableStateOf("") }
    var error by remember(packageName) { mutableStateOf("") }
    var forcePin by remember(packageName) { mutableStateOf(false) }
    var lockoutRemaining by remember(packageName) { mutableStateOf(0) }
    val engineState by app.lockEngine.state.collectAsState()

    val randomizedDigits = remember(packageName) {
        secureShuffleDigits()
    }

    val label = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        }.getOrDefault("Protected app")
    }

    LaunchedEffect(packageName, engineState) {
        while (app.lockEngine.isBlocked()) {
            lockoutRemaining = app.lockEngine.remainingLockoutSeconds()
            delay(1_000L)
        }
        lockoutRemaining = 0
    }

    fun showResult(result: LockEngine.AuthenticationResult) {
        when (result) {
            LockEngine.AuthenticationResult.SUCCESS -> {
                error = ""
                pin = ""
                onSuccess()
            }

            LockEngine.AuthenticationResult.BLOCKED -> {
                lockoutRemaining = app.lockEngine.remainingLockoutSeconds()
                error = "Too many failed attempts"
            }

            LockEngine.AuthenticationResult.INVALID_CREDENTIAL -> {
                pin = ""
                error = "Incorrect credential"
            }

            LockEngine.AuthenticationResult.NOT_PROTECTED,
            LockEngine.AuthenticationResult.NOT_CONFIGURED,
            LockEngine.AuthenticationResult.LIMITED_PROTECTION -> {
                error = "Protection is not currently available"
            }
        }
    }

    fun authenticatePin() {
        if (app.lockEngine.isBlocked()) {
            lockoutRemaining = app.lockEngine.remainingLockoutSeconds()
            return
        }
        showResult(
            app.lockEngine.authenticatePin(packageName, pin)
        )
    }

    fun authenticateBiometric() {
        val activity = context as? FragmentActivity ?: run {
            forcePin = true
            error = "Biometric authentication is unavailable"
            return
        }

        if (!app.lockEngine.isAuthenticationRequestActive(packageName)) {
            error = "This authentication request is no longer active"
            return
        }

        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK

        if (
            BiometricManager.from(context).canAuthenticate(authenticators) !=
                BiometricManager.BIOMETRIC_SUCCESS
        ) {
            forcePin = true
            error = "Biometric authentication is unavailable. Use PIN instead."
            return
        }

        val executor = ContextCompat.getMainExecutor(context)

        // BiometricPrompt can temporarily stop LockActivity. Tell both the
        // Activity and the accessibility service that this exact request has
        // entered the biometric hand-off.
        (activity as? LockActivity)?.setBiometricPromptActive(true)
        (activity as? LockActivity)?.notifyBiometricPromptState("IN_PROGRESS")

        BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    // IMPORTANT: complete the request and create the exact
                    // one-shot launch authorization BEFORE releasing the
                    // service's biometric-departure guard.
                    val lockActivity = activity as? LockActivity
                    lockActivity?.notifyBiometricPromptState("SUCCEEDED")

                    val result =
                        app.lockEngine.completeBiometricAuthentication(packageName)

                    if (result == LockEngine.AuthenticationResult.SUCCESS) {
                        // Do not route biometric success back through the
                        // Activity as the primary hand-off. On some Android/OEM
                        // builds BiometricPrompt can move/destroy LockActivity
                        // before this callback returns. The Accessibility
                        // service is already the owner of the protected-app
                        // foreground transaction, so it performs the exact
                        // package+request launch hand-off.
                        val handedOff =
                            lockActivity?.completeBiometricAuthenticationHandoff() == true

                        if (!handedOff) {
                            android.util.Log.w(
                                "AppLockDiag",
                                "Biometric handoff service did not accept " +
                                    "pkg=$packageName; using Activity fallback"
                            )
                            lockActivity?.completeAuthenticationFromBiometricFallback(
                                packageName
                            )
                        }
                    } else {
                        showResult(result)
                    }

                    // The service clears its request-bound biometric state
                    // after accepting the launch hand-off. For failure/fallback
                    // paths the Activity must release it here.
                    if (result != LockEngine.AuthenticationResult.SUCCESS) {
                        lockActivity?.setBiometricPromptActive(false)
                    }
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    val state =
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                        ) {
                            "CANCELLED"
                        } else {
                            "FAILED"
                        }

                    (activity as? LockActivity)?.notifyBiometricPromptState(state)
                    (activity as? LockActivity)?.setBiometricPromptActive(false)

                    if (
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        forcePin = true
                        error = ""
                    } else if (
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED
                    ) {
                        error = errString.toString()
                    }
                }

                override fun onAuthenticationFailed() {
                    error = "Biometric not recognized. Try again or use PIN."
                }
            }
        ).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock protected app")
                .setSubtitle(label)
                .setNegativeButtonText("Use PIN")
                .setConfirmationRequired(false)
                .build()
        )
    }

    val method = repo.getAuthMethod()
    val showBiometric = method == AuthMethod.BIOMETRIC && !forcePin
    val showPattern = method == AuthMethod.PATTERN && !forcePin
    val blocked = app.lockEngine.isBlocked()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding()
                        .padding(horizontal = 22.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = .13f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        "Protected app",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(Modifier.height(6.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "AppLock security check",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    when {
                        blocked -> {
                            Text(
                                if (lockoutRemaining > 0) {
                                    "Too many attempts. Try again in ${lockoutRemaining}s."
                                } else {
                                    "Temporarily locked. Try again shortly."
                                },
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        showBiometric -> {
                            Button(
                                onClick = ::authenticateBiometric,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Fingerprint,
                                    contentDescription = null
                                )
                                Spacer(Modifier.size(10.dp))
                                Text("Unlock with biometric")
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
                                if (error.isEmpty()) {
                                    "Draw your pattern"
                                } else {
                                    error
                                },
                                color = if (error.isEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )

                            Spacer(Modifier.height(14.dp))

                            PatternLockInput(
                                enabled = !blocked,
                                onComplete = { value ->
                                    showResult(
                                        app.lockEngine.authenticatePattern(
                                            packageName,
                                            value
                                        )
                                    )
                                }
                            )
                        }

                        else -> {
                            Text(
                                if (error.isEmpty()) "Enter your PIN" else error,
                                color = if (error.isEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )

                            Spacer(Modifier.height(12.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                repeat(8) { index ->
                                    Box(
                                        Modifier
                                            .size(11.dp)
                                            .background(
                                                if (index < pin.length) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                },
                                                CircleShape
                                            )
                                    )
                                }
                            }

                            Spacer(Modifier.height(20.dp))

                            val keys = randomizedDigits + listOf("⌫", "UNLOCK")

                            keys.chunked(3).forEach { row ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    row.forEach { key ->
                                        when (key) {
                                            "⌫" -> {
                                                Button(
                                                    onClick = {
                                                        if (pin.isNotEmpty()) {
                                                            pin = pin.dropLast(1)
                                                        }
                                                        error = ""
                                                    },
                                                    enabled = !blocked,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(56.dp),
                                                    shape = RoundedCornerShape(18.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Backspace,
                                                        contentDescription = "Delete"
                                                    )
                                                }
                                            }

                                            "UNLOCK" -> {
                                                Button(
                                                    onClick = ::authenticatePin,
                                                    enabled = !blocked && pin.length in 4..8,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(56.dp),
                                                    shape = RoundedCornerShape(18.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = null
                                                    )
                                                    Spacer(Modifier.size(5.dp))
                                                    Text("Unlock")
                                                }
                                            }

                                            else -> {
                                                Button(
                                                    onClick = {
                                                        if (!blocked && pin.length < 8) {
                                                            pin += key
                                                            error = ""
                                                        }
                                                    },
                                                    enabled = !blocked && pin.length < 8,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(56.dp),
                                                    shape = RoundedCornerShape(18.dp)
                                                ) {
                                                    Text(
                                                        key,
                                                        style = MaterialTheme.typography.titleLarge
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }

                            if (method == AuthMethod.BIOMETRIC && repo.hasPin()) {
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
                }
            }
        }
    }
}

@Composable
private fun PatternLockInput(
    enabled: Boolean,
    onComplete: (String) -> Unit
) {
    var selected by remember { mutableStateOf(emptyList<Int>()) }
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }

    fun addPoint(point: Int) {
        if (selected.contains(point)) return

        val next = selected.toMutableList()

        if (next.isNotEmpty()) {
            val middle = intermediatePoint(next.last(), point)
            if (middle != null && !next.contains(middle)) {
                next += middle
            }
        }

        if (!next.contains(point)) {
            next += point
        }

        selected = next
    }

    fun finishGesture() {
        if (!isDragging) return

        isDragging = false
        dragPosition = null

        val completed = selected
        selected = emptyList()

        if (completed.size >= 4) {
            onComplete(completed.joinToString("-"))
        }
    }

    Box(
        modifier = Modifier
            .size(280.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput

                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
                    )

                    down.consume()

                    val width = size.width.toFloat()
                    val height = size.height.toFloat()

                    val first = nearestPatternPoint(
                        down.position,
                        width,
                        height
                    )

                    if (first == null) {
                        selected = emptyList()
                        dragPosition = null
                        isDragging = false
                        return@awaitEachGesture
                    }

                    selected = emptyList()
                    isDragging = true
                    dragPosition = down.position
                    addPoint(first)

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

                            nearestPatternPoint(
                                change.position,
                                width,
                                height
                            )?.let(::addPoint)
                        } else {
                            change.consume()
                            finishGesture()
                            break
                        }
                    }
                }
            }
    ) {
        val activeColor = MaterialTheme.colorScheme.primary
        val inactiveColor =
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)
        val backgroundColor = MaterialTheme.colorScheme.background

        Canvas(Modifier.fillMaxSize()) {
            val positions = patternPositions(size.width, size.height)

            if (selected.size > 1) {
                selected.zipWithNext().forEach { (a, b) ->
                    drawLine(
                        color = activeColor,
                        start = positions[a],
                        end = positions[b],
                        strokeWidth = 10f
                    )
                }
            }

            if (isDragging && dragPosition != null && selected.isNotEmpty()) {
                drawLine(
                    color = activeColor.copy(alpha = .65f),
                    start = positions[selected.last()],
                    end = dragPosition!!,
                    strokeWidth = 7f
                )
            }

            positions.forEachIndexed { index, position ->
                val active = selected.contains(index)

                drawCircle(
                    color = if (active) activeColor else inactiveColor,
                    radius = if (active) 18f else 12f,
                    center = position
                )

                if (active) {
                    drawCircle(
                        color = backgroundColor,
                        radius = 6f,
                        center = position
                    )
                }
            }
        }
    }
}

private fun patternPositions(width: Float, height: Float): List<Offset> {
    val x1 = width * .2f
    val x2 = width * .5f
    val x3 = width * .8f
    val y1 = height * .2f
    val y2 = height * .5f
    val y3 = height * .8f

    return listOf(
        Offset(x1, y1), Offset(x2, y1), Offset(x3, y1),
        Offset(x1, y2), Offset(x2, y2), Offset(x3, y2),
        Offset(x1, y3), Offset(x2, y3), Offset(x3, y3)
    )
}

private fun nearestPatternPoint(
    position: Offset,
    width: Float,
    height: Float
): Int? {
    val positions = patternPositions(width, height)
    var bestIndex = -1
    var bestDistance = Float.MAX_VALUE

    positions.forEachIndexed { index, point ->
        val dx = position.x - point.x
        val dy = position.y - point.y
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }

    val threshold = minOf(width, height) * .19f
    return bestIndex.takeIf { it >= 0 && bestDistance <= threshold }
}

private fun intermediatePoint(from: Int, to: Int): Int? {
    val fromRow = from / 3
    val fromCol = from % 3
    val toRow = to / 3
    val toCol = to % 3

    val rowDiff = abs(fromRow - toRow)
    val colDiff = abs(fromCol - toCol)

    if (rowDiff == 0 && colDiff == 2) {
        return fromRow * 3 + 1
    }
    if (colDiff == 0 && rowDiff == 2) {
        return 3 + minOf(fromCol, toCol)
    }
    if (rowDiff == 2 && colDiff == 2) {
        return 4
    }

    return null
}

private fun secureShuffleDigits(): List<String> {
    val digits = mutableListOf(
        "0", "1", "2", "3", "4",
        "5", "6", "7", "8", "9"
    )
    val random = SecureRandom()

    for (index in digits.lastIndex downTo 1) {
        val swapIndex = random.nextInt(index + 1)
        val temp = digits[index]
        digits[index] = digits[swapIndex]
        digits[swapIndex] = temp
    }

    return digits
}
