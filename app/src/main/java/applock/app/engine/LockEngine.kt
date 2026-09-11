package applock.app.engine

import android.os.SystemClock
import applock.app.data.AppLockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local, deterministic App Lock decision engine.
 *
 * Responsibilities:
 * - Decide whether a protected package requires authentication.
 * - Keep the AccessibilityService hot path cheap.
 * - Keep authentication throttling in one centralized place.
 * - Prevent rapid duplicate accessibility events from launching
 *   multiple LockActivity instances.
 * - Record successful authentication only after authentication succeeds.
 * - Allow exactly one return transition after successful authentication.
 *
 * The AccessibilityService is responsible for observing foreground/window
 * transitions. This class owns the security decision and authentication state.
 */
class LockEngine(
    private val repository: AppLockRepository
) {

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
        BLOCKED,
        INVALID_CREDENTIAL,
        NOT_PROTECTED,
        NOT_CONFIGURED,
        LIMITED_PROTECTION
    }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    /*
     * Package most recently treated as the foreground package.
     *
     * This is informational state only. It is deliberately NOT used as a
     * permanent same-package suppression mechanism because AccessibilityService
     * does not guarantee a clean package sequence on every OEM/device.
     */
    private var lastProcessedPackage: String? = null

    /*
     * Short-lived duplicate-event suppression.
     *
     * AccessibilityService can emit several identical window events during
     * one transition. This prevents repeated LockActivity launches without
     * suppressing a genuine later re-entry.
     */
    private var lastDecisionPackage: String? = null
    private var lastDecisionElapsed: Long = 0L

    /*
     * Authentication return token.
     *
     * After successful authentication, LockActivity launches the protected
     * application. Android normally reports that protected package again.
     *
     * Exactly one matching visibility event is consumed as the authenticated
     * return. A later transition must authenticate again according to the
     * configured session rule.
     */
    private var authenticatedPackage: String? = null
    private var authenticatedReturnPending = false

    /*
     * Prevents repeated failed authentication attempts from being used for
     * unlimited guessing.
     *
     * This is process-local by design. A cold process restart clears the
     * throttle while the credential itself remains protected by SecureStorage.
     */
    private var failedAttempts = 0
    private var blockedUntilElapsed = 0L

    private companion object {
        const val DUPLICATE_DECISION_WINDOW_MS = 350L
        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 30_000L
    }

    /**
     * Called when AccessibilityService determines that a package became
     * visible/foreground.
     *
     * Returns true only when LockActivity should be launched.
     */
    @Synchronized
    fun onPackageVisible(packageName: String): Boolean {
        if (packageName.isBlank()) {
            return false
        }

        val now = SystemClock.elapsedRealtime()

        /*
         * Ignore only very short duplicate accessibility bursts.
         *
         * Do not use a long-lived package equality check here. Android/OEM
         * accessibility event ordering is not guaranteed to contain a
         * non-protected event between two genuine launches of the same app.
         */
        if (
            packageName == lastDecisionPackage &&
            now - lastDecisionElapsed < DUPLICATE_DECISION_WINDOW_MS
        ) {
            return false
        }

        lastDecisionPackage = packageName
        lastDecisionElapsed = now

        /*
         * A non-protected package establishes that the user has left the
         * protected application.
         *
         * This invalidates the one-time authenticated return token.
         */
        if (!repository.isProtected(packageName)) {
            authenticatedPackage = null
            authenticatedReturnPending = false

            lastProcessedPackage = packageName
            _state.value = State.IDLE
            return false
        }

        /*
         * Consume exactly one authenticated return event.
         */
        if (
            authenticatedReturnPending &&
            authenticatedPackage == packageName
        ) {
            authenticatedReturnPending = false
            authenticatedPackage = null

            lastProcessedPackage = packageName
            _state.value = State.UNLOCKED
            return false
        }

        /*
         * If the exact same protected package is continuously visible,
         * don't launch another lock screen for every accessibility event.
         *
         * The important distinction is that this is reset when a different
         * package is observed and can also be reset explicitly after service
         * lifecycle changes.
         */
        if (packageName == lastProcessedPackage) {
            return false
        }

        lastProcessedPackage = packageName

        /*
         * Fail closed when the prerequisites required for protection aren't
         * available.
         */
        if (!repository.accessibilityEnabled()) {
            _state.value = State.LIMITED_PROTECTION
            return false
        }

        if (!repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return false
        }

        /*
         * If authentication is temporarily blocked, do not launch another
         * authentication UI.
         */
        if (isBlocked()) {
            _state.value = State.TEMPORARILY_BLOCKED
            return false
        }

        _state.value = State.PROTECTED_APP_DETECTED
        _state.value = State.CHECKING_STATE

        val required = repository.shouldRequireAuth(packageName)

        if (required) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return true
        }

        _state.value = State.UNLOCKED
        return false
    }

    /**
     * Called when LockActivity becomes visible.
     */
    @Synchronized
    fun markAuthUiShown() {
        _state.value = if (isBlocked()) {
            State.TEMPORARILY_BLOCKED
        } else {
            State.SHOWING_AUTH
        }
    }

    /**
     * Returns whether authentication is currently temporarily blocked.
     */
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

    /**
     * Remaining lockout time in whole seconds.
     */
    @Synchronized
    fun remainingLockoutSeconds(): Int {
        val remaining = blockedUntilElapsed - SystemClock.elapsedRealtime()

        if (remaining <= 0L) {
            if (blockedUntilElapsed != 0L) {
                blockedUntilElapsed = 0L
                failedAttempts = 0

                if (_state.value == State.TEMPORARILY_BLOCKED) {
                    _state.value = State.AUTHENTICATION_REQUIRED
                }
            }

            return 0
        }

        return ((remaining + 999L) / 1_000L)
            .coerceAtLeast(1L)
            .toInt()
    }

    /**
     * Authenticate using the configured PIN.
     */
    @Synchronized
    fun authenticatePin(
        packageName: String,
        pin: String
    ): AuthenticationResult {
        if (isBlocked()) {
            _state.value = State.TEMPORARILY_BLOCKED
            return AuthenticationResult.BLOCKED
        }

        if (packageName.isBlank() || !repository.isProtected(packageName)) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return AuthenticationResult.NOT_PROTECTED
        }

        if (!repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return AuthenticationResult.NOT_CONFIGURED
        }

        if (!repository.hasPin()) {
            _state.value = State.LIMITED_PROTECTION
            return AuthenticationResult.NOT_CONFIGURED
        }

        if (repository.verifyPin(pin)) {
            failedAttempts = 0
            blockedUntilElapsed = 0L

            return if (unlock(packageName)) {
                AuthenticationResult.SUCCESS
            } else {
                AuthenticationResult.LIMITED_PROTECTION
            }
        }

        registerFailedAuthentication()
        return if (isBlocked()) {
            AuthenticationResult.BLOCKED
        } else {
            AuthenticationResult.INVALID_CREDENTIAL
        }
    }

    /**
     * Authenticate using the configured pattern.
     */
    @Synchronized
    fun authenticatePattern(
        packageName: String,
        pattern: String
    ): AuthenticationResult {
        if (isBlocked()) {
            _state.value = State.TEMPORARILY_BLOCKED
            return AuthenticationResult.BLOCKED
        }

        if (packageName.isBlank() || !repository.isProtected(packageName)) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return AuthenticationResult.NOT_PROTECTED
        }

        if (!repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return AuthenticationResult.NOT_CONFIGURED
        }

        if (!repository.hasPattern()) {
            _state.value = State.LIMITED_PROTECTION
            return AuthenticationResult.NOT_CONFIGURED
        }

        if (repository.verifyPattern(pattern)) {
            failedAttempts = 0
            blockedUntilElapsed = 0L

            return if (unlock(packageName)) {
                AuthenticationResult.SUCCESS
            } else {
                AuthenticationResult.LIMITED_PROTECTION
            }
        }

        registerFailedAuthentication()
        return if (isBlocked()) {
            AuthenticationResult.BLOCKED
        } else {
            AuthenticationResult.INVALID_CREDENTIAL
        }
    }

    /**
     * Completes authentication after Android BiometricPrompt reports success.
     *
     * The biometric cryptographic verification itself is performed by
     * BiometricPrompt. Therefore there is no PIN/pattern comparison here.
     */
    @Synchronized
    fun completeBiometricAuthentication(
        packageName: String
    ): AuthenticationResult {
        if (isBlocked()) {
            _state.value = State.TEMPORARILY_BLOCKED
            return AuthenticationResult.BLOCKED
        }

        if (packageName.isBlank() || !repository.isProtected(packageName)) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return AuthenticationResult.NOT_PROTECTED
        }

        if (!repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return AuthenticationResult.NOT_CONFIGURED
        }

        if (repository.getAuthMethod() != applock.app.domain.AuthMethod.BIOMETRIC) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return AuthenticationResult.LIMITED_PROTECTION
        }

        failedAttempts = 0
        blockedUntilElapsed = 0L

        return if (unlock(packageName)) {
            AuthenticationResult.SUCCESS
        } else {
            AuthenticationResult.LIMITED_PROTECTION
        }
    }

    /**
     * Records one failed PIN/pattern attempt.
     */
    @Synchronized
    private fun registerFailedAuthentication() {
        failedAttempts++

        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            blockedUntilElapsed =
                SystemClock.elapsedRealtime() + LOCKOUT_DURATION_MS

            _state.value = State.TEMPORARILY_BLOCKED
        } else {
            _state.value = State.AUTHENTICATION_REQUIRED
        }
    }

    /**
     * Explicitly clears authentication throttling.
     *
     * Useful when the application performs a deliberate security-state reset.
     */
    @Synchronized
    fun resetAuthenticationThrottle() {
        failedAttempts = 0
        blockedUntilElapsed = 0L

        if (_state.value == State.TEMPORARILY_BLOCKED) {
            _state.value = State.AUTHENTICATION_REQUIRED
        }
    }

    /**
     * Records a successful authentication and creates the one-time
     * authenticated return token.
     */
    @Synchronized
    fun unlock(packageName: String): Boolean {
        if (packageName.isBlank()) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return false
        }

        if (!repository.isProtected(packageName)) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return false
        }

        if (!repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return false
        }

        /*
         * LockActivity currently calls unlock() after LockScreen has already
         * authenticated successfully. Treat that second call as idempotent
         * for the same package instead of creating another inconsistent state.
         */
        if (
            authenticatedReturnPending &&
            authenticatedPackage == packageName
        ) {
            _state.value = State.UNLOCKED
            return true
        }

        if (!repository.markUnlocked(packageName)) {
            _state.value = State.AUTHENTICATION_REQUIRED
            return false
        }

        authenticatedPackage = packageName
        authenticatedReturnPending = true

        /*
         * The next protected-package event must be allowed through even if
         * Android delivers it immediately after authentication.
         */
        lastDecisionPackage = null
        lastDecisionElapsed = 0L

        failedAttempts = 0
        blockedUntilElapsed = 0L

        _state.value = State.UNLOCKED
        return true
    }

    /**
     * Optional pre-authentication check for callers that want to validate
     * state before displaying authentication UI.
     */
    @Synchronized
    fun preAuthenticate(packageName: String): Boolean {
        if (packageName.isBlank()) {
            return false
        }

        if (!repository.isProtected(packageName)) {
            return false
        }

        if (!repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return false
        }

        if (isBlocked()) {
            _state.value = State.TEMPORARILY_BLOCKED
            return false
        }

        _state.value = State.AUTHENTICATION_REQUIRED
        return true
    }

    /**
     * Called when the accessibility service is interrupted or recreated.
     *
     * All process-local transition state is intentionally discarded.
     */
    @Synchronized
    fun reset() {
        _state.value = State.IDLE

        lastProcessedPackage = null
        lastDecisionPackage = null
        lastDecisionElapsed = 0L

        authenticatedPackage = null
        authenticatedReturnPending = false

        /*
         * Authentication throttling is deliberately not carried across a
         * service lifecycle reset. The credential remains protected by the
         * repository's secure storage.
         */
        failedAttempts = 0
        blockedUntilElapsed = 0L
    }

    /**
     * Explicitly marks a package as outside the protected-app transition.
     *
     * This is useful when the AccessibilityService observes our own lock UI.
     */
    @Synchronized
    fun onNonProtectedPackageVisible(packageName: String) {
        if (packageName.isBlank()) {
            return
        }

        authenticatedPackage = null
        authenticatedReturnPending = false

        lastProcessedPackage = packageName

        /*
         * Don't retain the previous package's duplicate-event timestamp.
         * A later return to a protected app must be evaluated normally.
         */
        lastDecisionPackage = null
        lastDecisionElapsed = 0L

        _state.value = State.IDLE
    }
}