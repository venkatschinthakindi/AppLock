package applock.app.ui.lock
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication
import applock.app.domain.AuthMethod
import applock.app.engine.LockEngine
import applock.app.security.SecurityAuthenticator
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

@Composable
fun SecurityGateScreen(
    onAuthenticated: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication
    val repo = app.repository
    val theme by repo.theme.collectAsState()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var forcePin by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf(emptyList<Int>()) }
    var lockoutRemaining by remember { mutableStateOf(0) }

    val lifecycleOwner = LocalLifecycleOwner.current
    var method by remember { mutableStateOf(repo.getAuthMethod()) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val latest = repo.getAuthMethod()
                if (latest != method) {
                    method = latest
                    forcePin = false
                    pin = ""
                    pattern = emptyList()
                    error = ""
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        while (SecurityAuthenticator.isBlocked()) {
            lockoutRemaining =
                SecurityAuthenticator.remainingSeconds()

            delay(1_000L)
        }

        lockoutRemaining = 0
    }

    fun showResult(
        result: LockEngine.AuthenticationResult
    ) {
        when (result) {
            LockEngine.AuthenticationResult.SUCCESS -> {
                error = ""
                onAuthenticated()
            }

            LockEngine.AuthenticationResult.BLOCKED -> {
                lockoutRemaining =
                    SecurityAuthenticator.remainingSeconds()

                error = "Too many failed attempts"
            }

            LockEngine.AuthenticationResult.INVALID_CREDENTIAL -> {
                pin = ""
                error = "Incorrect credential"
            }

            else -> {
                error =
                    "Security authentication is not available"
            }
        }
    }

    fun authenticatePin() {
        if (SecurityAuthenticator.isBlocked()) {
            return
        }

        showResult(
            SecurityAuthenticator.authenticatePin(
                app,
                pin
            )
        )
    }

    fun authenticatePattern(
        value: String
    ) {
        if (SecurityAuthenticator.isBlocked()) {
            return
        }

        showResult(
            SecurityAuthenticator.authenticatePattern(
                app,
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
                        SecurityAuthenticator
                            .authenticateBiometric(app)
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
                .setTitle("App Lock security check")
                .setSubtitle(
                    "Authenticate before accessing device management controls"
                )
                .setNegativeButtonText("Use PIN")
                .setConfirmationRequired(false)
                .build()
        )
    }

    val showBiometric =
        method == AuthMethod.BIOMETRIC && !forcePin

    val showPattern =
        method == AuthMethod.PATTERN && !forcePin

    val blocked =
        SecurityAuthenticator.isBlocked()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            Modifier
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
                    Modifier
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
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint =
                            MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier.size(40.dp)
                    )
                }

                Spacer(
                    Modifier.height(16.dp)
                )

                Text(
                    "App Lock protection",
                    style =
                        MaterialTheme.typography.headlineMedium,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    "Security-sensitive system controls",
                    style =
                        MaterialTheme.typography.titleMedium,
                    fontWeight =
                        FontWeight.SemiBold
                )

                Spacer(
                    Modifier.height(6.dp)
                )

                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint =
                            MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier.size(18.dp)
                    )

                    Spacer(
                        Modifier.size(6.dp)
                    )

                    Text(
                        "Authentication required",
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant
                    )
                }

                Spacer(
                    Modifier.height(24.dp)
                )

                when {

                    blocked -> {
                        Text(
                            "Too many attempts. " +
                                "Try again in " +
                                "${lockoutRemaining}s.",
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
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                            shape =
                                RoundedCornerShape(
                                    theme.cornerRadius.dp
                                )
                        ) {
                            Icon(
                                Icons.Default.Fingerprint,
                                contentDescription =
                                    null
                            )

                            Spacer(
                                Modifier.size(10.dp)
                            )

                            Text(
                                "Authenticate with biometric"
                            )
                        }

                        if (repo.hasPin()) {
                            TextButton(
                                onClick = {
                                    forcePin = true
                                    error = ""
                                }
                            ) {
                                Text(
                                    "Use PIN instead"
                                )
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

                        Spacer(
                            Modifier.height(14.dp)
                        )

                        PatternSetupGrid(
                            selected = pattern,
                            onChanged = { completed ->
                                if (completed.size >= 4) {
                                    authenticatePattern(
                                        completed.joinToString("-")
                                    )
                                    pattern = emptyList()
                                } else {
                                    pattern = emptyList()
                                }
                            }
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

                        Spacer(
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

                        Spacer(
                            Modifier.height(20.dp)
                        )

                        val keys = listOf(
                            "1", "2", "3",
                            "4", "5", "6",
                            "7", "8", "9",
                            "⌫", "0", "AUTH"
                        )

                        keys.chunked(3).forEach { row ->

                            Row(
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

                                        "AUTH" -> {
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

                                                Spacer(
                                                    Modifier.size(5.dp)
                                                )

                                                Text(
                                                    "Authenticate"
                                                )
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

                            Spacer(
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
                    Spacer(
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

                Spacer(
                    Modifier.height(12.dp)
                )
            }
        }
    }
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

    fun distanceBetween(
        first: Offset,
        second: Offset
    ): Float {
        val dx = first.x - second.x
        val dy = first.y - second.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun hitTest(
        position: Offset,
        width: Float,
        height: Float
    ): Int? {
        val threshold = minOf(width, height) * 0.14f

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

            val hasSkippedMiddlePoint =
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

            if (hasSkippedMiddlePoint) {
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

    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val background = MaterialTheme.colorScheme.background

    Canvas(
        modifier = Modifier
            .size(290.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { position ->
                        val index = hitTest(
                            position = position,
                            width = size.width.toFloat(),
                            height = size.height.toFloat()
                        )

                        if (index != null) {
                            isDragging = true
                            dragSelection = appendPoint(emptyList(), index)
                            dragPosition = position
                        }
                    },
                    onDrag = { change, _ ->
                        if (!isDragging) {
                            return@detectDragGestures
                        }

                        dragPosition = change.position

                        val index = hitTest(
                            position = change.position,
                            width = size.width.toFloat(),
                            height = size.height.toFloat()
                        )

                        if (index != null) {
                            dragSelection = appendPoint(dragSelection, index)
                        }
                    },
                    onDragEnd = {
                        if (isDragging) {
                            isDragging = false
                            dragPosition = null

                            val completedPattern = dragSelection

                            if (completedPattern.size >= 4) {
                                onChanged(completedPattern)
                            } else {
                                onChanged(emptyList())
                            }
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        dragPosition = null
                        dragSelection = selected
                    }
                )
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
