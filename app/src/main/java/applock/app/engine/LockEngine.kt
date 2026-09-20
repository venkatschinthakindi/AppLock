package applock.app.engine

import android.os.SystemClock
import applock.app.domain.AuthMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local security state machine for protected-app launches.
 *
 * Design rules (all of them are security invariants, not optimisations):
 *
 * 1. FAIL CLOSED. Any state we are not sure about resolves to "challenge".
 * 2. A session (authorisation) is bound to one package and is destroyed by
 *    ANY user-visible departure: launcher, recents, another app, screen off,
 *    AppLock itself, or a protection/credential configuration change.
 * 3. A session is NOT destroyed by surfaces the user cannot "leave" through
 *    (our own lock UI, the IME, permission/share/system dialogs). Those park
 *    the session instead, so the user is never re-challenged mid-use.
 * 4. Every authentication request carries a monotonic requestId. Only the
 *    owner of a requestId may cancel or complete it, so a stale lock screen
 *    can never cancel the challenge created for a newer launch.
 * 5. Being throttled (too many wrong attempts) NEVER means "let the user in".
 *    A request is still created and the lock UI shows the countdown.
 */
class LockEngine(
    private val repository: LockPolicySource,
    /** Seam for tests; production uses the monotonic system clock. */
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }
) {

    enum class State {
        IDLE, PROTECTED_APP_DETECTED, CHECKING_STATE, AUTHENTICATION_REQUIRED,
        SHOWING_AUTH, UNLOCKED, LIMITED_PROTECTION, TEMPORARILY_BLOCKED
    }

    enum class AuthenticationResult {
        SUCCESS, BLOCKED, INVALID_CREDENTIAL, NOT_PROTECTED, NOT_CONFIGURED,
        LIMITED_PROTECTION
    }

    /** Result of a foreground evaluation. */
    data class Decision(
        val packageName: String,
        val requireAuth: Boolean,
        val requestId: Long
    ) {
        companion object {
            val NONE = Decision("", false, 0L)
        }
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Package currently believed to own the foreground. */
    private var foregroundPackage: String? = null

    /** Package with a live, authenticated session. */
    private var authorizedPackage: String? = null

    /** Session parked behind a non-departure surface (IME, dialog, our lock UI). */
    private var parkedPackage: String? = null

    private var activeRequestId = 0L
    private var activeRequestPackage: String? = null
    private var requestCounter = 0L

    private var failedAttempts = 0
    private var blockedUntilElapsed = 0L

    private companion object {
        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 30_000L
    }

    // ---------------------------------------------------------------- inputs

    /**
     * A real, launchable application package owns the foreground.
     * This covers protected apps, other apps, and the launcher.
     */
    @Synchronized
    fun onForegroundApp(packageName: String): Decision {
        if (packageName.isBlank()) return Decision.NONE

        if (foregroundPackage != packageName) {
            // A different app is on screen: any parked session is gone for good,
            // and any pending challenge for the previous package is void.
            if (parkedPackage != null && parkedPackage != packageName) parkedPackage = null
            if (authorizedPackage != null && authorizedPackage != packageName) authorizedPackage = null
            if (activeRequestPackage != null && activeRequestPackage != packageName) {
                android.util.Log.d(
                    "AppLockDiag",
                    "ENGINE invalidating active request for '$activeRequestPackage' " +
                        "(id=$activeRequestId) because foreground changed to '$packageName' " +
                        "-- if this fires while a PIN is being entered, THIS is the bug"
                )
                invalidateRequestLocked()
            }
            foregroundPackage = packageName
        }

        // Returning to the package whose session was parked behind an IME, a
        // permission dialog or our own lock UI restores it. This must happen
        // whether or not the tracked foreground package changed: parking does
        // not move the foreground, so requiring a change here would have
        // re-challenged the user every time the keyboard closed.
        if (parkedPackage == packageName) {
            authorizedPackage = packageName
            parkedPackage = null
        }

        return evaluateLocked(packageName)
    }

    /**
     * A surface the user cannot "leave the app" through is on top: the IME,
     * a permission dialog, a share sheet, a system picker, or our own lock UI.
     * The session is parked, never destroyed, and any pending challenge stays
     * alive because the protected app is still the thing behind it.
     */
    @Synchronized
    fun onNonDepartureSurface() {
        if (authorizedPackage != null) {
            parkedPackage = authorizedPackage
            authorizedPackage = null
        }
    }

    /**
     * The user demonstrably left the protected app: launcher, recents, the
     * task switcher, or any other real application. Everything is torn down
     * and the next entry is a cold entry.
     */
    @Synchronized
    fun onUserLeftForeground() {
        clearSessionLocked()
        invalidateRequestLocked()
        foregroundPackage = null
        if (_state.value != State.TEMPORARILY_BLOCKED) _state.value = State.IDLE
    }

    /** Screen off / device locked: hardest possible boundary. */
    @Synchronized
    fun onScreenOff() = onUserLeftForeground()

    /** AppLock's own dashboard is an explicit boundary. */
    @Synchronized
    fun onAppLockVisible() = onUserLeftForeground()

    /** Our lock/gate UI is visible. It must not disturb session or request state. */
    @Synchronized
    fun onSecurityUiVisible() {
        if (authorizedPackage != null) {
            parkedPackage = authorizedPackage
            authorizedPackage = null
        }
    }

    // ------------------------------------------------------------ evaluation

    private fun evaluateLocked(packageName: String): Decision {
        if (!repository.isProtected(packageName)) {
            // Not protected: no session may survive here.
            clearSessionLocked()
            invalidateRequestLocked()
            if (_state.value != State.TEMPORARILY_BLOCKED) _state.value = State.IDLE
            return Decision.NONE
        }

        if (authorizedPackage == packageName) {
            _state.value = State.UNLOCKED
            return Decision.NONE
        }

        // A challenge already exists for this exact package: re-assert it.
        // (The caller is responsible for not launching duplicate lock UIs; it
        //  needs this so a dropped Activity start can be retried.)
        if (activeRequestPackage == packageName && activeRequestId != 0L) {
            _state.value = if (isBlocked()) State.TEMPORARILY_BLOCKED else State.SHOWING_AUTH
            return Decision(packageName, true, activeRequestId)
        }

        if (!repository.authenticationConfigured()) {
            // No credential: we cannot challenge. Report limited protection
            // rather than pretending the app is unlocked.
            _state.value = State.LIMITED_PROTECTION
            return Decision.NONE
        }

        _state.value = State.PROTECTED_APP_DETECTED
        _state.value = State.CHECKING_STATE

        // Session rule may still cover this entry (timed rules only).
        if (!isBlocked() && !repository.shouldRequireAuth(packageName)) {
            authorizedPackage = packageName
            parkedPackage = null
            _state.value = State.UNLOCKED
            return Decision.NONE
        }

        activeRequestId = nextRequestId()
        activeRequestPackage = packageName
        _state.value = if (isBlocked()) State.TEMPORARILY_BLOCKED else State.AUTHENTICATION_REQUIRED
        return Decision(packageName, true, activeRequestId)
    }

    // --------------------------------------------------------------- queries

    @Synchronized
    fun pendingRequest(): Decision {
        val pkg = activeRequestPackage
        return if (pkg != null && activeRequestId != 0L) {
            Decision(pkg, true, activeRequestId)
        } else {
            Decision.NONE
        }
    }

    @Synchronized
    fun hasActiveSession(): Boolean = authorizedPackage != null

    @Synchronized
    fun sessionPackage(): String? = authorizedPackage

    @Synchronized
    fun isRequestActive(packageName: String, requestId: Long): Boolean =
        packageName.isNotBlank() &&
            requestId != 0L &&
            activeRequestPackage == packageName &&
            activeRequestId == requestId

    /** Package-scoped variant retained for the authentication UI. */
    @Synchronized
    fun isAuthenticationRequestActive(packageName: String): Boolean =
        packageName.isNotBlank() &&
            activeRequestPackage == packageName &&
            activeRequestId != 0L

    @Synchronized
    fun isAuthorizedForLaunch(packageName: String): Boolean =
        packageName.isNotBlank() && authorizedPackage == packageName

    @Synchronized
    fun markAuthUiShown() {
        _state.value = when {
            isBlocked() -> State.TEMPORARILY_BLOCKED
            activeRequestPackage != null -> State.SHOWING_AUTH
            else -> State.AUTHENTICATION_REQUIRED
        }
    }

    @Synchronized
    fun isBlocked(): Boolean {
        val now = elapsedRealtime()
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
        val remaining = blockedUntilElapsed - elapsedRealtime()
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

    // -------------------------------------------------------- authentication

    @Synchronized
    fun authenticatePin(packageName: String, pin: String): AuthenticationResult {
        if (isBlocked()) {
            _state.value = State.TEMPORARILY_BLOCKED
            return AuthenticationResult.BLOCKED
        }
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
        if (isBlocked()) {
            _state.value = State.TEMPORARILY_BLOCKED
            return AuthenticationResult.BLOCKED
        }
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
        if (isBlocked()) {
            _state.value = State.TEMPORARILY_BLOCKED
            return AuthenticationResult.BLOCKED
        }
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
    fun unlock(packageName: String): Boolean =
        completeCredentialAuthentication(packageName) == AuthenticationResult.SUCCESS

    private fun completeCredentialAuthentication(packageName: String): AuthenticationResult {
        if (!isAuthenticationRequestActive(packageName)) return invalidContext()
        if (!repository.isProtected(packageName)) {
            invalidateRequestLocked()
            return AuthenticationResult.NOT_PROTECTED
        }
        if (!repository.markUnlocked(packageName)) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return AuthenticationResult.LIMITED_PROTECTION
        }

        authorizedPackage = packageName
        parkedPackage = null
        foregroundPackage = packageName
        invalidateRequestLocked()
        failedAttempts = 0
        blockedUntilElapsed = 0L
        _state.value = State.UNLOCKED
        return AuthenticationResult.SUCCESS
    }

    private fun failedCredential(): AuthenticationResult {
        failedAttempts++
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            blockedUntilElapsed = elapsedRealtime() + LOCKOUT_DURATION_MS
            _state.value = State.TEMPORARILY_BLOCKED
            return AuthenticationResult.BLOCKED
        }
        _state.value = State.AUTHENTICATION_REQUIRED
        return AuthenticationResult.INVALID_CREDENTIAL
    }

    private fun invalidContext(): AuthenticationResult {
        _state.value = if (repository.authenticationConfigured()) {
            State.AUTHENTICATION_REQUIRED
        } else {
            State.LIMITED_PROTECTION
        }
        return AuthenticationResult.LIMITED_PROTECTION
    }

    // ----------------------------------------------------- request ownership

    /**
     * Token-scoped cancellation. A lock screen may only cancel the request it
     * was actually created for; a stale instance can never void a newer one.
     */
    @Synchronized
    fun cancelRequest(packageName: String, requestId: Long) {
        if (!isRequestActive(packageName, requestId)) return
        invalidateRequestLocked()
        if (_state.value != State.TEMPORARILY_BLOCKED) _state.value = State.IDLE
    }

    /** Service-level cancellation (package scoped, used on hard boundaries). */
    @Synchronized
    fun cancelAuthenticationForPackage(packageName: String) {
        if (packageName.isBlank()) return
        if (activeRequestPackage == packageName) invalidateRequestLocked()
        if (authorizedPackage == packageName) authorizedPackage = null
        if (parkedPackage == packageName) parkedPackage = null
        if (_state.value != State.TEMPORARILY_BLOCKED) _state.value = State.IDLE
    }

    @Synchronized
    fun resetAuthenticationThrottle() {
        failedAttempts = 0
        blockedUntilElapsed = 0L
        if (_state.value == State.TEMPORARILY_BLOCKED) _state.value = State.AUTHENTICATION_REQUIRED
    }

    /** Configuration boundary: destroy every session and pending challenge. */
    @Synchronized
    fun resetTransitionState() = onUserLeftForeground()

    @Synchronized
    fun reset() {
        onUserLeftForeground()
        failedAttempts = 0
        blockedUntilElapsed = 0L
    }

    // ------------------------------------------------------------- internals

    private fun clearSessionLocked() {
        authorizedPackage = null
        parkedPackage = null
    }

    private fun invalidateRequestLocked() {
        activeRequestId = 0L
        activeRequestPackage = null
    }

    private fun nextRequestId(): Long {
        requestCounter = if (requestCounter == Long.MAX_VALUE) 1L else requestCounter + 1L
        return requestCounter
    }
}
