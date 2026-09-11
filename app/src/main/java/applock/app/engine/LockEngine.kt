package applock.app.engine

import android.os.SystemClock
import applock.app.data.AppLockRepository
import applock.app.domain.AuthMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Native security decision engine.
 *
 * UI is allowed to collect state and render controls, but credential verification,
 * failed-attempt throttling, session creation and protected-app authorization live here.
 * This class never performs network, ad, billing or analytics work.
 */
class LockEngine(private val repository: AppLockRepository) {
    enum class State {
        IDLE,
        PROTECTED_APP_DETECTED,
        CHECKING_STATE,
        AUTHENTICATION_REQUIRED,
        SHOWING_AUTH,
        UNLOCKED,
        LIMITED_PROTECTION,
        TEMPORARILY_BLOCKED
    }

    enum class AuthenticationResult {
        SUCCESS,
        INVALID_CREDENTIAL,
        BLOCKED,
        NOT_PROTECTED,
        NOT_CONFIGURED,
        LIMITED_PROTECTION
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private var lastLaunchPackage: String? = null
    private var lastLaunchElapsed = 0L
    private var failedAttempts = 0
    private var blockedUntilElapsed = 0L

    private companion object {
        const val DUPLICATE_EVENT_WINDOW_MS = 250L
        const val MAX_ATTEMPTS = 5
        const val LOCKOUT_MS = 30_000L
    }

    @Synchronized
    fun onPackageVisible(packageName: String): Boolean {
        val nowElapsed = SystemClock.elapsedRealtime()
        if (packageName == lastLaunchPackage && nowElapsed - lastLaunchElapsed < DUPLICATE_EVENT_WINDOW_MS) {
            return false
        }

        lastLaunchPackage = packageName
        lastLaunchElapsed = nowElapsed

        if (!repository.isProtected(packageName)) {
            _state.value = State.IDLE
            return false
        }

        if (!repository.secureStorageHealthy() || !repository.accessibilityEnabled()) {
            _state.value = State.LIMITED_PROTECTION
            return false
        }

        if (!repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return false
        }

        _state.value = State.PROTECTED_APP_DETECTED
        _state.value = State.CHECKING_STATE

        val required = repository.shouldRequireAuth(packageName)
        _state.value = if (required) State.AUTHENTICATION_REQUIRED else State.UNLOCKED
        return required
    }

    @Synchronized
    fun markAuthUiShown() {
        _state.value = if (isBlocked()) State.TEMPORARILY_BLOCKED else State.SHOWING_AUTH
    }

    @Synchronized
    fun authenticatePin(packageName: String, pin: String): AuthenticationResult {
        if (!preAuthenticate(packageName, AuthMethod.PIN)) return preflightResult(packageName)
        if (isBlocked()) {
            _state.value = State.TEMPORARILY_BLOCKED
            return AuthenticationResult.BLOCKED
        }

        val valid = repository.verifyPin(pin)
        return if (valid) {
            completeSuccessfulAuthentication(packageName)
        } else {
            recordFailure()
            AuthenticationResult.INVALID_CREDENTIAL
        }
    }

    @Synchronized
    fun authenticatePattern(packageName: String, pattern: String): AuthenticationResult {
        if (!preAuthenticate(packageName, AuthMethod.PATTERN)) return preflightResult(packageName)
        if (isBlocked()) {
            _state.value = State.TEMPORARILY_BLOCKED
            return AuthenticationResult.BLOCKED
        }

        val valid = repository.verifyPattern(pattern)
        return if (valid) {
            completeSuccessfulAuthentication(packageName)
        } else {
            recordFailure()
            AuthenticationResult.INVALID_CREDENTIAL
        }
    }

    /** Called only from the Android BiometricPrompt success callback. */
    @Synchronized
    fun completeBiometricAuthentication(packageName: String): AuthenticationResult {
        if (!repository.isProtected(packageName)) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return AuthenticationResult.NOT_PROTECTED
        }
        if (!repository.secureStorageHealthy() || !repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return AuthenticationResult.LIMITED_PROTECTION
        }
        return completeSuccessfulAuthentication(packageName)
    }

    /** Compatibility method for existing callers; it never bypasses authentication. */
    @Synchronized
    fun unlock(packageName: String): Boolean {
        return completeBiometricAuthentication(packageName) == AuthenticationResult.SUCCESS
    }

    @Synchronized
    fun isBlocked(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (blockedUntilElapsed == 0L) return false
        if (now >= blockedUntilElapsed) {
            blockedUntilElapsed = 0L
            failedAttempts = 0
            if (_state.value == State.TEMPORARILY_BLOCKED) {
                _state.value = State.AUTHENTICATION_REQUIRED
            }
            return false
        }
        return true
    }

    @Synchronized
    fun remainingLockoutSeconds(): Int {
        if (!isBlocked()) return 0
        val remaining = blockedUntilElapsed - SystemClock.elapsedRealtime()
        return ((remaining + 999L) / 1000L).toInt().coerceAtLeast(1)
    }

    @Synchronized
    fun resetAuthenticationThrottle() {
        failedAttempts = 0
        blockedUntilElapsed = 0L
    }

    @Synchronized
    fun reset() {
        _state.value = State.IDLE
        lastLaunchPackage = null
        lastLaunchElapsed = 0L
        resetAuthenticationThrottle()
    }

    private fun preAuthenticate(packageName: String, expectedMethod: AuthMethod): Boolean {
        if (packageName.isBlank() || !repository.isProtected(packageName)) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return false
        }
        if (!repository.secureStorageHealthy() || !repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return false
        }
        if (repository.getAuthMethod() != expectedMethod &&
            !(expectedMethod == AuthMethod.PIN && repository.getAuthMethod() == AuthMethod.BIOMETRIC)
        ) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return false
        }
        return true
    }

    private fun preflightResult(packageName: String): AuthenticationResult {
        if (packageName.isBlank() || !repository.isProtected(packageName)) {
            return AuthenticationResult.NOT_PROTECTED
        }
        if (!repository.secureStorageHealthy() || !repository.authenticationConfigured()) {
            return AuthenticationResult.NOT_CONFIGURED
        }
        return AuthenticationResult.NOT_CONFIGURED
    }

    private fun recordFailure() {
        failedAttempts++
        if (failedAttempts >= MAX_ATTEMPTS) {
            blockedUntilElapsed = SystemClock.elapsedRealtime() + LOCKOUT_MS
            _state.value = State.TEMPORARILY_BLOCKED
        } else {
            _state.value = State.AUTHENTICATION_REQUIRED
        }
    }

    private fun completeSuccessfulAuthentication(packageName: String): AuthenticationResult {
        if (!repository.markUnlocked(packageName)) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return AuthenticationResult.NOT_PROTECTED
        }
        resetAuthenticationThrottle()
        _state.value = State.UNLOCKED
        return AuthenticationResult.SUCCESS
    }
}
