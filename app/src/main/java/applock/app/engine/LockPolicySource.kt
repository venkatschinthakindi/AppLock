package applock.app.engine

import applock.app.domain.AuthMethod

/**
 * Everything the lock engine is allowed to ask about configuration and
 * credentials.
 *
 * The engine deliberately depends on this narrow interface rather than on the
 * concrete repository, so the security state machine can be exercised by
 * deterministic tests without Android.
 */
interface LockPolicySource {
    fun isProtected(packageName: String): Boolean
    fun authenticationConfigured(): Boolean
    fun shouldRequireAuth(packageName: String): Boolean
    fun markUnlocked(packageName: String): Boolean
    fun hasPin(): Boolean
    fun hasPattern(): Boolean
    fun verifyPin(pin: String): Boolean
    fun verifyPattern(pattern: String): Boolean
    fun getAuthMethod(): AuthMethod
}
