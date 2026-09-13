package applock.app.security

import android.os.SystemClock
import applock.app.AppLockApplication
import applock.app.engine.LockEngine

/**
 * Local authentication gate used for security-sensitive Android management
 * surfaces. It never grants an app unlock/session token.
 */
object SecurityAuthenticator {
    private const val MAX_FAILED_ATTEMPTS = 5
    private const val LOCKOUT_MS = 30_000L

    private var failedAttempts = 0
    private var blockedUntil = 0L

    @Synchronized
    fun isBlocked(): Boolean {
        if (blockedUntil <= SystemClock.elapsedRealtime()) {
            blockedUntil = 0L
            failedAttempts = 0
            return false
        }
        return true
    }

    @Synchronized
    fun remainingSeconds(): Int {
        val remaining = blockedUntil - SystemClock.elapsedRealtime()
        if (remaining <= 0L) {
            blockedUntil = 0L
            failedAttempts = 0
            return 0
        }
        return ((remaining + 999L) / 1000L).toInt().coerceAtLeast(1)
    }

    @Synchronized
    fun authenticatePin(app: AppLockApplication, pin: String): LockEngine.AuthenticationResult {
        if (isBlocked()) return LockEngine.AuthenticationResult.BLOCKED
        if (!app.repository.authenticationConfigured() || !app.repository.hasPin()) {
            return LockEngine.AuthenticationResult.NOT_CONFIGURED
        }
        if (app.repository.verifyPin(pin)) {
            clearFailures()
            return LockEngine.AuthenticationResult.SUCCESS
        }
        registerFailure()
        return if (isBlocked()) LockEngine.AuthenticationResult.BLOCKED
        else LockEngine.AuthenticationResult.INVALID_CREDENTIAL
    }

    @Synchronized
    fun authenticatePattern(app: AppLockApplication, pattern: String): LockEngine.AuthenticationResult {
        if (isBlocked()) return LockEngine.AuthenticationResult.BLOCKED
        if (!app.repository.authenticationConfigured() || !app.repository.hasPattern()) {
            return LockEngine.AuthenticationResult.NOT_CONFIGURED
        }
        if (app.repository.verifyPattern(pattern)) {
            clearFailures()
            return LockEngine.AuthenticationResult.SUCCESS
        }
        registerFailure()
        return if (isBlocked()) LockEngine.AuthenticationResult.BLOCKED
        else LockEngine.AuthenticationResult.INVALID_CREDENTIAL
    }

    @Synchronized
    fun authenticateBiometric(app: AppLockApplication): LockEngine.AuthenticationResult {
        if (isBlocked()) return LockEngine.AuthenticationResult.BLOCKED
        if (!app.repository.authenticationConfigured()) {
            return LockEngine.AuthenticationResult.NOT_CONFIGURED
        }
        clearFailures()
        return LockEngine.AuthenticationResult.SUCCESS
    }

    @Synchronized
    private fun registerFailure() {
        failedAttempts++
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            blockedUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS
        }
    }

    @Synchronized
    private fun clearFailures() {
        failedAttempts = 0
        blockedUntil = 0L
    }
}
