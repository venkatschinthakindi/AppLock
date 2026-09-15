package applock.app.engine

import android.os.SystemClock
import applock.app.data.AppLockRepository
import applock.app.domain.AuthMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local security state machine for protected-app launches.
 *
 * A credential is valid only for the exact authentication request that was
 * created for the current foreground package. A successful authentication
 * creates a package-bound foreground authorization; it is cleared whenever
 * the foreground package actually changes.
 */
class LockEngine(private val repository: AppLockRepository) {
    enum class State {
        IDLE, PROTECTED_APP_DETECTED, CHECKING_STATE, AUTHENTICATION_REQUIRED,
        SHOWING_AUTH, UNLOCKED, LIMITED_PROTECTION, TEMPORARILY_BLOCKED
    }

    enum class AuthenticationResult {
        SUCCESS, BLOCKED, INVALID_CREDENTIAL, NOT_PROTECTED, NOT_CONFIGURED,
        LIMITED_PROTECTION
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private var currentForegroundPackage: String? = null
    private var authorizedForegroundPackage: String? = null
    private var activeRequestId = 0L
    private var activeRequestPackage: String? = null
    private var authenticatedReturnRequestId = 0L
    private var authenticatedReturnPackage: String? = null

    private var failedAttempts = 0
    private var blockedUntilElapsed = 0L

    private companion object {
        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 30_000L
    }

    /** Returns true only on a new protected-app entry that needs the lock UI. */
    @Synchronized
    fun onPackageVisible(packageName: String): Boolean {
        if (packageName.isBlank()) return false

        val changed = currentForegroundPackage != packageName
        if (changed) {
            currentForegroundPackage = packageName
            authorizedForegroundPackage = null
            invalidateAuthenticationRequestLocked()
            authenticatedReturnRequestId = 0L
            authenticatedReturnPackage = null
        }

        if (!repository.isProtected(packageName)) {
            _state.value = State.IDLE
            return false
        }

        if (consumeAuthenticatedReturnLocked(packageName)) {
            authorizedForegroundPackage = packageName
            _state.value = State.UNLOCKED
            return false
        }

        // The user is already authenticated and is still in this app.
        if (authorizedForegroundPackage == packageName) {
            _state.value = State.UNLOCKED
            return false
        }

        // The request is already being displayed for this exact package.
        if (activeRequestPackage == packageName && activeRequestId != 0L) {
            _state.value = State.SHOWING_AUTH
            return false
        }

        if (isBlocked()) {
            _state.value = State.TEMPORARILY_BLOCKED
            return false
        }

        if (!repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return false
        }

        _state.value = State.PROTECTED_APP_DETECTED
        _state.value = State.CHECKING_STATE

        if (!repository.shouldRequireAuth(packageName)) {
            authorizedForegroundPackage = packageName
            _state.value = State.UNLOCKED
            return false
        }

        activeRequestId = nextRequestId(activeRequestId)
        activeRequestPackage = packageName
        _state.value = State.AUTHENTICATION_REQUIRED
        return true
    }

    @Synchronized
    fun markAuthUiShown() {
        _state.value = when {
            isBlocked() -> State.TEMPORARILY_BLOCKED
            activeRequestPackage != null -> State.SHOWING_AUTH
            else -> State.AUTHENTICATION_REQUIRED
        }
    }

    @Synchronized
    fun isAuthenticationRequestActive(packageName: String): Boolean =
        packageName.isNotBlank() &&
            currentForegroundPackage == packageName &&
            activeRequestPackage == packageName &&
            activeRequestId != 0L

    @Synchronized
    fun isAuthenticatedReturnPendingFor(packageName: String): Boolean =
        packageName.isNotBlank() &&
            currentForegroundPackage == packageName &&
            authenticatedReturnPackage == packageName &&
            authenticatedReturnRequestId != 0L

    @Synchronized
    fun isAuthorizedForLaunch(packageName: String): Boolean =
        packageName.isNotBlank() &&
            currentForegroundPackage == packageName &&
            authorizedForegroundPackage == packageName &&
            (authenticatedReturnPackage == packageName ||
                _state.value == State.UNLOCKED)

    @Synchronized
    fun isBlocked(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (blockedUntilElapsed <= now) {
            if (blockedUntilElapsed != 0L) {
                blockedUntilElapsed = 0L
                failedAttempts = 0
                if (_state.value == State.TEMPORARILY_BLOCKED) {
                    _state.value = State.AUTHENTICATION_REQUIRED
                }
            }
            return false
        }
        return true
    }

    @Synchronized
    fun remainingLockoutSeconds(): Int {
        val remaining = blockedUntilElapsed - SystemClock.elapsedRealtime()
        if (remaining <= 0L) {
            blockedUntilElapsed = 0L
            failedAttempts = 0
            if (_state.value == State.TEMPORARILY_BLOCKED) {
                _state.value = State.AUTHENTICATION_REQUIRED
            }
            return 0
        }
        return ((remaining + 999L) / 1_000L).coerceAtLeast(1L).toInt()
    }

    @Synchronized
    fun authenticatePin(packageName: String, pin: String): AuthenticationResult {
        if (isBlocked()) return AuthenticationResult.BLOCKED.also { _state.value = State.TEMPORARILY_BLOCKED }
        if (!isAuthenticationRequestActive(packageName)) return invalidContext()
        if (!repository.authenticationConfigured() || !repository.hasPin()) {
            _state.value = State.LIMITED_PROTECTION
            return AuthenticationResult.NOT_CONFIGURED
        }
        if (!repository.verifyPin(pin)) return failedCredential()
        return completeCredentialAuthentication(packageName)
    }

    @Synchronized
    fun authenticatePattern(packageName: String, pattern: String): AuthenticationResult {
        if (isBlocked()) return AuthenticationResult.BLOCKED.also { _state.value = State.TEMPORARILY_BLOCKED }
        if (!isAuthenticationRequestActive(packageName)) return invalidContext()
        if (!repository.authenticationConfigured() || !repository.hasPattern()) {
            _state.value = State.LIMITED_PROTECTION
            return AuthenticationResult.NOT_CONFIGURED
        }
        if (!repository.verifyPattern(pattern)) return failedCredential()
        return completeCredentialAuthentication(packageName)
    }

    @Synchronized
    fun completeBiometricAuthentication(packageName: String): AuthenticationResult {
        if (isBlocked()) return AuthenticationResult.BLOCKED.also { _state.value = State.TEMPORARILY_BLOCKED }
        if (!isAuthenticationRequestActive(packageName)) return invalidContext()
        if (!repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return AuthenticationResult.NOT_CONFIGURED
        }
        if (repository.getAuthMethod() != AuthMethod.BIOMETRIC) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return AuthenticationResult.LIMITED_PROTECTION
        }
        return completeCredentialAuthentication(packageName)
    }

    @Synchronized
    fun unlock(packageName: String): Boolean {
        return completeCredentialAuthentication(packageName) == AuthenticationResult.SUCCESS
    }

    @Synchronized
    fun preAuthenticate(packageName: String): Boolean {
        if (packageName.isBlank() || !repository.isProtected(packageName)) return false
        if (!repository.authenticationConfigured() || isBlocked()) return false
        currentForegroundPackage = packageName
        authorizedForegroundPackage = null
        authenticatedReturnRequestId = 0L
        authenticatedReturnPackage = null
        activeRequestId = nextRequestId(activeRequestId)
        activeRequestPackage = packageName
        _state.value = State.AUTHENTICATION_REQUIRED
        return true
    }

    @Synchronized
    fun resetAuthenticationThrottle() {
        failedAttempts = 0
        blockedUntilElapsed = 0L
        if (_state.value == State.TEMPORARILY_BLOCKED) _state.value = State.AUTHENTICATION_REQUIRED
    }

    /** Explicit AppLock boundary: AppLock itself is never a protected target. */
    @Synchronized
    fun onAppLockVisible() {
        currentForegroundPackage = null
        authorizedForegroundPackage = null
        invalidateAuthenticationRequestLocked()
        authenticatedReturnRequestId = 0L
        authenticatedReturnPackage = null
        _state.value = State.IDLE
    }

    @Synchronized
    fun resetTransitionState() {
        currentForegroundPackage = null
        authorizedForegroundPackage = null
        invalidateAuthenticationRequestLocked()
        authenticatedReturnRequestId = 0L
        authenticatedReturnPackage = null
        _state.value = State.IDLE
    }

    @Synchronized
    fun reset() {
        resetTransitionState()
        failedAttempts = 0
        blockedUntilElapsed = 0L
    }

    @Synchronized
    fun onNonProtectedPackageVisible(packageName: String) {
        if (packageName.isBlank()) return
        if (currentForegroundPackage == packageName) {
            currentForegroundPackage = null
        }
        authorizedForegroundPackage = null
        invalidateAuthenticationRequestLocked()
        authenticatedReturnRequestId = 0L
        authenticatedReturnPackage = null
        _state.value = State.IDLE
    }

    @Synchronized
    fun cancelAuthenticationForPackage(packageName: String) {
        if (packageName.isBlank()) return
        if (activeRequestPackage == packageName) invalidateAuthenticationRequestLocked()
        if (authenticatedReturnPackage == packageName) {
            authenticatedReturnRequestId = 0L
            authenticatedReturnPackage = null
        }
        if (authorizedForegroundPackage == packageName) authorizedForegroundPackage = null
        if (_state.value != State.TEMPORARILY_BLOCKED) _state.value = State.IDLE
    }

    private fun completeCredentialAuthentication(packageName: String): AuthenticationResult {
        if (!isAuthenticationRequestActive(packageName)) return invalidContext()
        if (!repository.isProtected(packageName)) {
            invalidateAuthenticationRequestLocked()
            return AuthenticationResult.NOT_PROTECTED
        }
        if (!repository.markUnlocked(packageName)) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return AuthenticationResult.LIMITED_PROTECTION
        }

        authenticatedReturnRequestId = activeRequestId
        authenticatedReturnPackage = packageName
        // Mark the package authorized immediately. The return token remains
        // separately pending so the first post-authentication accessibility
        // event can be consumed without re-locking.
        authorizedForegroundPackage = packageName
        invalidateAuthenticationRequestLocked()
        failedAttempts = 0
        blockedUntilElapsed = 0L
        _state.value = State.UNLOCKED
        return AuthenticationResult.SUCCESS
    }

    private fun failedCredential(): AuthenticationResult {
        failedAttempts++
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            blockedUntilElapsed = SystemClock.elapsedRealtime() + LOCKOUT_DURATION_MS
            _state.value = State.TEMPORARILY_BLOCKED
            return AuthenticationResult.BLOCKED
        }
        _state.value = State.AUTHENTICATION_REQUIRED
        return AuthenticationResult.INVALID_CREDENTIAL
    }

    private fun invalidContext(): AuthenticationResult {
        _state.value = if (repository.authenticationConfigured()) State.AUTHENTICATION_REQUIRED else State.LIMITED_PROTECTION
        return AuthenticationResult.LIMITED_PROTECTION
    }

    private fun consumeAuthenticatedReturnLocked(packageName: String): Boolean {
        if (authenticatedReturnRequestId != 0L && authenticatedReturnPackage == packageName) {
            authenticatedReturnRequestId = 0L
            authenticatedReturnPackage = null
            return true
        }
        return false
    }

    private fun invalidateAuthenticationRequestLocked() {
        activeRequestId = 0L
        activeRequestPackage = null
    }

    private fun nextRequestId(previous: Long): Long = if (previous == Long.MAX_VALUE) 1L else previous + 1L
}
