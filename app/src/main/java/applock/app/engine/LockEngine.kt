package applock.app.engine

import android.os.SystemClock
import applock.app.domain.AuthMethod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-local security state machine for protected-app launches.
 *
 * Security invariants:
 *
 * 1. FAIL CLOSED.
 * 2. Every authentication request has a unique requestId.
 * 3. A stale request can never cancel or complete a newer request.
 * 4. Successful authentication creates a ONE-SHOT launch authorization
 *    bound to the exact package + requestId.
 * 5. Launch authorization is consumed exactly once.
 * 6. Authentication UI visibility is never treated as authentication.
 * 7. Leaving a protected app destroys the session and active request.
 * 8. Lockout never grants access.
 */
class LockEngine(
    private val repository: LockPolicySource,
    private val elapsedRealtime: () -> Long = {
        SystemClock.elapsedRealtime()
    }
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

    data class Decision(
        val packageName: String,
        val requireAuth: Boolean,
        val requestId: Long
    ) {
        companion object {
            val NONE = Decision("", false, 0L)
        }
    }

    /**
     * Exact authorization produced by a successful authentication.
     *
     * It belongs to one authentication request only.
     */
    data class LaunchAuthorization(
        val packageName: String,
        val requestId: Long
    )

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private var foregroundPackage: String? = null

    private var authorizedPackage: String? = null

    private var parkedPackage: String? = null

    private var activeRequestId = 0L
    private var activeRequestPackage: String? = null

    private var requestCounter = 0L

    /**
     * Authentication success creates this authorization.
     *
     * It is deliberately separate from authorizedPackage.
     *
     * authorizedPackage = session state
     * launchAuthorization = one-shot launch permission
     */
    private var launchAuthorization: LaunchAuthorization? = null

    private var failedAttempts = 0
    private var blockedUntilElapsed = 0L

    private companion object {
        const val MAX_FAILED_ATTEMPTS = 5
        const val LOCKOUT_DURATION_MS = 30_000L
    }

    // ---------------------------------------------------------------------
    // FOREGROUND
    // ---------------------------------------------------------------------

    @Synchronized
    fun onForegroundApp(packageName: String): Decision {
        if (packageName.isBlank()) {
            return Decision.NONE
        }

        /*
         * An active authentication transaction owns the transition.
         *
         * Do not replace request A with request B merely because an
         * intermediate foreground callback arrived.
         */
        if (
            activeRequestPackage != null &&
            activeRequestId != 0L &&
            activeRequestPackage != packageName
        ) {
            return Decision(
                packageName = activeRequestPackage!!,
                requireAuth = true,
                requestId = activeRequestId
            )
        }

        if (foregroundPackage != packageName) {

            if (
                parkedPackage != null &&
                parkedPackage != packageName
            ) {
                parkedPackage = null
            }

            if (
                authorizedPackage != null &&
                authorizedPackage != packageName
            ) {
                authorizedPackage = null
                clearLaunchAuthorizationLocked()
            }

            if (
                activeRequestPackage != null &&
                activeRequestPackage != packageName
            ) {
                android.util.Log.d(
                    "AppLockDiag",
                    "ENGINE invalidating active request for " +
                        "'$activeRequestPackage' id=$activeRequestId " +
                        "because foreground changed to '$packageName'"
                )

                invalidateRequestLocked()
            }

            foregroundPackage = packageName
        }

        /*
         * A parked session belongs to this same package.
         */
        if (parkedPackage == packageName) {
            authorizedPackage = packageName
            parkedPackage = null
        }

        return evaluateLocked(packageName)
    }

    /**
     * Non-departure surfaces such as:
     * - IME
     * - permission dialogs
     * - system dialogs
     * - share sheets
     * - our own authentication UI
     */
    @Synchronized
    fun onNonDepartureSurface() {
        if (authorizedPackage != null) {
            parkedPackage = authorizedPackage
            authorizedPackage = null
        }
    }

    /**
     * User demonstrably left the protected application.
     */
    @Synchronized
    fun onUserLeftForeground() {
        clearSessionLocked()
        invalidateRequestLocked()
        clearLaunchAuthorizationLocked()

        foregroundPackage = null

        if (_state.value != State.TEMPORARILY_BLOCKED) {
            _state.value = State.IDLE
        }
    }

    /**
     * Screen-off is an authentication boundary.
     */
    @Synchronized
    fun onScreenOff() {
        clearSessionLocked()
        invalidateRequestLocked()
        clearLaunchAuthorizationLocked()

        foregroundPackage = null

        if (_state.value != State.TEMPORARILY_BLOCKED) {
            _state.value = State.IDLE
        }
    }

    @Synchronized
    fun onAppLockVisible() {
        onUserLeftForeground()
    }

    /**
     * LockActivity/security UI itself must not destroy the request.
     */
    @Synchronized
    fun onSecurityUiVisible() {
        if (authorizedPackage != null) {
            parkedPackage = authorizedPackage
            authorizedPackage = null
        }
    }

    // ---------------------------------------------------------------------
    // EVALUATION
    // ---------------------------------------------------------------------

    private fun evaluateLocked(packageName: String): Decision {

        if (!repository.isProtected(packageName)) {
            clearSessionLocked()
            invalidateRequestLocked()
            clearLaunchAuthorizationLocked()

            if (_state.value != State.TEMPORARILY_BLOCKED) {
                _state.value = State.IDLE
            }

            return Decision.NONE
        }

        if (authorizedPackage == packageName) {
            _state.value = State.UNLOCKED
            return Decision.NONE
        }

        /*
         * Existing request for the same exact package.
         */
        if (
            activeRequestPackage == packageName &&
            activeRequestId != 0L
        ) {
            _state.value =
                if (isBlocked()) {
                    State.TEMPORARILY_BLOCKED
                } else {
                    State.SHOWING_AUTH
                }

            return Decision(
                packageName,
                true,
                activeRequestId
            )
        }

        if (!repository.authenticationConfigured()) {
            _state.value = State.LIMITED_PROTECTION
            return Decision.NONE
        }

        _state.value = State.PROTECTED_APP_DETECTED
        _state.value = State.CHECKING_STATE

        if (
            !isBlocked() &&
            !repository.shouldRequireAuth(packageName)
        ) {
            authorizedPackage = packageName
            parkedPackage = null
            clearLaunchAuthorizationLocked()

            _state.value = State.UNLOCKED

            return Decision.NONE
        }

        activeRequestId = nextRequestId()
        activeRequestPackage = packageName

        _state.value =
            if (isBlocked()) {
                State.TEMPORARILY_BLOCKED
            } else {
                State.AUTHENTICATION_REQUIRED
            }

        return Decision(
            packageName,
            true,
            activeRequestId
        )
    }

    // ---------------------------------------------------------------------
    // QUERIES
    // ---------------------------------------------------------------------

    @Synchronized
    fun pendingRequest(): Decision {
        val pkg = activeRequestPackage

        return if (
            pkg != null &&
            activeRequestId != 0L
        ) {
            Decision(
                pkg,
                true,
                activeRequestId
            )
        } else {
            Decision.NONE
        }
    }

    @Synchronized
    fun hasActiveSession(): Boolean =
        authorizedPackage != null

    @Synchronized
    fun sessionPackage(): String? =
        authorizedPackage

    @Synchronized
    fun isRequestActive(
        packageName: String,
        requestId: Long
    ): Boolean =
        packageName.isNotBlank() &&
            requestId != 0L &&
            activeRequestPackage == packageName &&
            activeRequestId == requestId

    @Synchronized
    fun isAuthenticationRequestActive(
        packageName: String
    ): Boolean =
        packageName.isNotBlank() &&
            activeRequestPackage == packageName &&
            activeRequestId != 0L

    /**
     * Legacy package-only query.
     *
     * Keep only if other parts of the application still need it.
     *
     * DO NOT use this for the final LockActivity launch authorization.
     */
    @Synchronized
    fun isAuthorizedForLaunch(packageName: String): Boolean =
        packageName.isNotBlank() &&
            authorizedPackage == packageName

    /**
     * Exact one-shot authorization check.
     *
     * This does NOT consume the authorization.
     */
    @Synchronized
    fun hasLaunchAuthorization(
        packageName: String,
        requestId: Long
    ): Boolean {
        val authorization = launchAuthorization
            ?: return false

        return authorization.packageName == packageName &&
            authorization.requestId == requestId
    }

    /**
     * Exact one-shot authorization.
     *
     * Returns true exactly once.
     */
    @Synchronized
    fun consumeLaunchAuthorization(
        packageName: String,
        requestId: Long
    ): Boolean {

        val authorization = launchAuthorization
            ?: return false

        if (
            authorization.packageName != packageName ||
            authorization.requestId != requestId
        ) {
            return false
        }

        launchAuthorization = null

        return true
    }

    @Synchronized
    fun markAuthUiShown() {
        _state.value = when {
            isBlocked() ->
                State.TEMPORARILY_BLOCKED

            activeRequestPackage != null ->
                State.SHOWING_AUTH

            else ->
                State.AUTHENTICATION_REQUIRED
        }
    }

    @Synchronized
    fun isBlocked(): Boolean {
        val now = elapsedRealtime()

        if (blockedUntilElapsed <= now) {

            if (blockedUntilElapsed != 0L) {
                blockedUntilElapsed = 0L
                failedAttempts = 0

                if (
                    _state.value ==
                    State.TEMPORARILY_BLOCKED
                ) {
                    _state.value =
                        State.AUTHENTICATION_REQUIRED
                }
            }

            return false
        }

        return true
    }

    @Synchronized
    fun remainingLockoutSeconds(): Int {
        val remaining =
            blockedUntilElapsed - elapsedRealtime()

        if (remaining <= 0L) {
            blockedUntilElapsed = 0L
            failedAttempts = 0

            if (
                _state.value ==
                State.TEMPORARILY_BLOCKED
            ) {
                _state.value =
                    State.AUTHENTICATION_REQUIRED
            }

            return 0
        }

        return (
            (remaining + 999L) / 1_000L
        ).coerceAtLeast(1L).toInt()
    }

    // ---------------------------------------------------------------------
    // AUTHENTICATION
    // ---------------------------------------------------------------------

    @Synchronized
    fun authenticatePin(
        packageName: String,
        pin: String
    ): AuthenticationResult {

        if (isBlocked()) {
            _state.value =
                State.TEMPORARILY_BLOCKED

            return AuthenticationResult.BLOCKED
        }

        if (!isAuthenticationRequestActive(packageName)) {
            return invalidContext()
        }

        if (
            !repository.authenticationConfigured() ||
            !repository.hasPin()
        ) {
            _state.value =
                State.LIMITED_PROTECTION

            return AuthenticationResult.NOT_CONFIGURED
        }

        if (!repository.verifyPin(pin)) {
            return failedCredential()
        }

        return completeCredentialAuthentication(
            packageName
        )
    }

    @Synchronized
    fun authenticatePattern(
        packageName: String,
        pattern: String
    ): AuthenticationResult {

        if (isBlocked()) {
            _state.value =
                State.TEMPORARILY_BLOCKED

            return AuthenticationResult.BLOCKED
        }

        if (!isAuthenticationRequestActive(packageName)) {
            return invalidContext()
        }

        if (
            !repository.authenticationConfigured() ||
            !repository.hasPattern()
        ) {
            _state.value =
                State.LIMITED_PROTECTION

            return AuthenticationResult.NOT_CONFIGURED
        }

        if (!repository.verifyPattern(pattern)) {
            return failedCredential()
        }

        return completeCredentialAuthentication(
            packageName
        )
    }

    @Synchronized
    fun completeBiometricAuthentication(
        packageName: String
    ): AuthenticationResult {

        android.util.Log.d(
            "AppLockDiag",
            "completeBiometricAuthentication START pkg=$packageName " +
                "activeReqId=$activeRequestId active=${isAuthenticationRequestActive(packageName)} " +
                "method=${repository.getAuthMethod()}"
        )

        if (isBlocked()) {
            _state.value =
                State.TEMPORARILY_BLOCKED

            return AuthenticationResult.BLOCKED
        }

        if (!isAuthenticationRequestActive(packageName)) {
            return invalidContext()
        }

        if (!repository.authenticationConfigured()) {
            _state.value =
                State.LIMITED_PROTECTION

            return AuthenticationResult.LIMITED_PROTECTION
        }

        if (
            repository.getAuthMethod() !=
            AuthMethod.BIOMETRIC
        ) {
            _state.value =
                State.AUTHENTICATION_REQUIRED

            return AuthenticationResult.LIMITED_PROTECTION
        }

        return completeCredentialAuthentication(
            packageName
        )
    }

    @Synchronized
    fun unlock(packageName: String): Boolean =
        completeCredentialAuthentication(
            packageName
        ) == AuthenticationResult.SUCCESS

    private fun completeCredentialAuthentication(
        packageName: String
    ): AuthenticationResult {

        if (!isAuthenticationRequestActive(packageName)) {
            return invalidContext()
        }

        if (!repository.isProtected(packageName)) {
            invalidateRequestLocked()
            clearLaunchAuthorizationLocked()

            return AuthenticationResult.NOT_PROTECTED
        }

        /*
         * Capture the exact request BEFORE invalidating it.
         */
        val authenticatedRequestId =
            activeRequestId

        android.util.Log.d(
            "AppLockDiag",
            "completeCredentialAuthentication AUTHORIZING pkg=$packageName " +
                "requestId=$authenticatedRequestId"
        )

        if (authenticatedRequestId == 0L) {
            return invalidContext()
        }

        if (!repository.markUnlocked(packageName)) {
            _state.value =
                State.AUTHENTICATION_REQUIRED

            return AuthenticationResult.LIMITED_PROTECTION
        }

        authorizedPackage = packageName
        parkedPackage = null
        foregroundPackage = packageName

        /*
         * Create exact one-shot launch authorization.
         */
        launchAuthorization =
            LaunchAuthorization(
                packageName = packageName,
                requestId = authenticatedRequestId
            )

        android.util.Log.d(
            "AppLockDiag",
            "completeCredentialAuthentication LAUNCH_AUTH_CREATED " +
                "pkg=$packageName requestId=$authenticatedRequestId"
        )

        /*
         * The authentication request itself is now complete.
         * The launch authorization survives only as the exact one-shot
         * bridge from authentication to starting the protected application.
         */
        invalidateRequestLocked()

        failedAttempts = 0
        blockedUntilElapsed = 0L

        _state.value = State.UNLOCKED

        return AuthenticationResult.SUCCESS
    }

    private fun failedCredential(): AuthenticationResult {

        failedAttempts++

        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            blockedUntilElapsed =
                elapsedRealtime() +
                    LOCKOUT_DURATION_MS

            _state.value =
                State.TEMPORARILY_BLOCKED

            return AuthenticationResult.BLOCKED
        }

        _state.value =
            State.AUTHENTICATION_REQUIRED

        return AuthenticationResult.INVALID_CREDENTIAL
    }

    private fun invalidContext(): AuthenticationResult {
        _state.value =
            if (repository.authenticationConfigured()) {
                State.AUTHENTICATION_REQUIRED
            } else {
                State.LIMITED_PROTECTION
            }

        return AuthenticationResult.LIMITED_PROTECTION
    }

    // ---------------------------------------------------------------------
    // REQUEST OWNERSHIP
    // ---------------------------------------------------------------------

    /**
     * Exact request cancellation.
     */
    @Synchronized
    fun cancelRequest(
        packageName: String,
        requestId: Long
    ) {
        if (!isRequestActive(packageName, requestId)) {
            return
        }

        invalidateRequestLocked()

        /*
         * A cancelled request must never leave behind a launch authorization
         * from an earlier transaction.
         */
        clearLaunchAuthorizationLocked()

        if (
            _state.value !=
            State.TEMPORARILY_BLOCKED
        ) {
            _state.value = State.IDLE
        }
    }

    /**
     * Package-level hard cancellation.
     */
    @Synchronized
    fun cancelAuthenticationForPackage(
        packageName: String
    ) {
        if (packageName.isBlank()) {
            return
        }

        if (activeRequestPackage == packageName) {
            invalidateRequestLocked()
            clearLaunchAuthorizationLocked()
        }

        if (authorizedPackage == packageName) {
            authorizedPackage = null
            clearLaunchAuthorizationLocked()
        }

        if (parkedPackage == packageName) {
            parkedPackage = null
        }

        if (
            _state.value !=
            State.TEMPORARILY_BLOCKED
        ) {
            _state.value = State.IDLE
        }
    }

    @Synchronized
    fun resetAuthenticationThrottle() {
        failedAttempts = 0
        blockedUntilElapsed = 0L

        if (
            _state.value ==
            State.TEMPORARILY_BLOCKED
        ) {
            _state.value =
                State.AUTHENTICATION_REQUIRED
        }
    }

    @Synchronized
    fun resetTransitionState() {
        onUserLeftForeground()
    }

    @Synchronized
    fun reset() {
        onUserLeftForeground()

        failedAttempts = 0
        blockedUntilElapsed = 0L
    }

    // ---------------------------------------------------------------------
    // INTERNALS
    // ---------------------------------------------------------------------

    private fun clearSessionLocked() {
        authorizedPackage = null
        parkedPackage = null
    }

    private fun clearLaunchAuthorizationLocked() {
        launchAuthorization = null
    }

    private fun invalidateRequestLocked() {
        activeRequestId = 0L
        activeRequestPackage = null
    }

    private fun nextRequestId(): Long {
        requestCounter =
            if (requestCounter == Long.MAX_VALUE) {
                1L
            } else {
                requestCounter + 1L
            }

        return requestCounter
    }
}