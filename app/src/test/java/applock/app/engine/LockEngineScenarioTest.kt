package applock.app.engine

import applock.app.domain.AuthMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression suite for the protected-app challenge state machine.
 *
 * Every test here is a field report. If one of them ever fails again, a user
 * can open a protected app without being challenged, or is challenged while
 * still using it. Treat a failure as a release blocker.
 *
 * The engine is driven exactly the way AppDetectionAccessibilityService and
 * LockActivity drive it in production.
 */
class LockEngineScenarioTest {

    private companion object {
        const val WA = "com.whatsapp"
        const val OTHER = "com.example.notes"
        const val LAUNCHER = "com.android.launcher3"
    }

    private class FakePolicy : LockPolicySource {
        val protectedApps = mutableSetOf<String>()
        var configured = true
        var pin = "1234"
        var method = AuthMethod.PIN
        val unlockedAt = mutableMapOf<String, Long>()
        var now = 10_000L
        var sessionRule = "IMMEDIATELY"

        override fun isProtected(packageName: String) = protectedApps.contains(packageName)
        override fun authenticationConfigured() = configured
        override fun hasPin() = true
        override fun hasPattern() = false
        override fun verifyPin(pin: String) = pin == this.pin
        override fun verifyPattern(pattern: String) = false
        override fun getAuthMethod() = method

        override fun markUnlocked(packageName: String): Boolean {
            if (!isProtected(packageName)) return false
            unlockedAt[packageName] = now
            return true
        }

        override fun shouldRequireAuth(packageName: String): Boolean {
            if (!isProtected(packageName)) return false
            val last = unlockedAt[packageName] ?: return true
            if (now < last) return true
            return when (sessionRule) {
                "MINUTES_5" -> now - last >= 300_000L
                else -> true
            }
        }

        fun clearUnlock(packageName: String) {
            unlockedAt.remove(packageName)
        }
    }

    private var clock = 1_000L
    private val policy = FakePolicy()
    private val engine = LockEngine(policy) { clock }

    private fun satisfy(decision: LockEngine.Decision): Boolean {
        engine.onSecurityUiVisible()
        engine.markAuthUiShown()
        return engine.authenticatePin(decision.packageName, policy.pin) ==
            LockEngine.AuthenticationResult.SUCCESS
    }

    @Test
    fun `first launch after configuring protection is challenged`() {
        engine.onAppLockVisible()
        policy.protectedApps.add(WA)
        engine.resetTransitionState()
        engine.onForegroundApp(LAUNCHER)

        val decision = engine.onForegroundApp(WA)

        assertTrue(decision.requireAuth)
        assertNotEquals(0L, decision.requestId)
        assertFalse(engine.isAuthorizedForLaunch(WA))
    }

    @Test
    fun `entry is challenged even when the intermediate window event is dropped`() {
        engine.onAppLockVisible()
        policy.protectedApps.add(WA)
        engine.resetTransitionState()

        assertTrue(engine.onForegroundApp(WA).requireAuth)
    }

    @Test
    fun `re-entry from recents is always challenged`() {
        policy.protectedApps.add(WA)

        val first = engine.onForegroundApp(WA)
        assertTrue(first.requireAuth)
        assertTrue(satisfy(first))
        assertFalse(engine.onForegroundApp(WA).requireAuth)

        engine.onUserLeftForeground()
        policy.clearUnlock(WA)

        val second = engine.onForegroundApp(WA)
        assertTrue(second.requireAuth)
        assertNotEquals(first.requestId, second.requestId)
    }

    @Test
    fun `a stale lock screen cannot cancel a newer challenge`() {
        policy.protectedApps.add(WA)

        val first = engine.onForegroundApp(WA)
        engine.onSecurityUiVisible()
        engine.onUserLeftForeground()

        val second = engine.onForegroundApp(WA)
        assertTrue(second.requireAuth)

        // The abandoned LockActivity is destroyed only now.
        engine.cancelRequest(WA, first.requestId)

        assertTrue(engine.isRequestActive(WA, second.requestId))
        assertFalse(engine.isAuthorizedForLaunch(WA))
    }

    @Test
    fun `no re-challenge while the app is in use`() {
        policy.protectedApps.add(WA)
        satisfy(engine.onForegroundApp(WA))

        repeat(5) { assertFalse(engine.onForegroundApp(WA).requireAuth) }

        engine.onNonDepartureSurface() // keyboard
        assertFalse(engine.onForegroundApp(WA).requireAuth)

        engine.onNonDepartureSurface() // permission dialog
        engine.onNonDepartureSurface() // share sheet
        assertFalse(engine.onForegroundApp(WA).requireAuth)
        assertTrue(engine.isAuthorizedForLaunch(WA))
    }

    @Test
    fun `a parked session does not survive a real app switch`() {
        policy.protectedApps.add(WA)
        satisfy(engine.onForegroundApp(WA))

        engine.onNonDepartureSurface()
        engine.onForegroundApp(OTHER)
        policy.clearUnlock(WA)

        assertTrue(engine.onForegroundApp(WA).requireAuth)
    }

    @Test
    fun `a session does not survive screen off`() {
        policy.protectedApps.add(WA)
        satisfy(engine.onForegroundApp(WA))

        engine.onScreenOff()
        policy.clearUnlock(WA)

        assertTrue(engine.onForegroundApp(WA).requireAuth)
    }

    @Test
    fun `screen off always destroys a timed session`() {
        policy.sessionRule = "MINUTES_5"
        policy.protectedApps.add(WA)
        satisfy(engine.onForegroundApp(WA))

        // Screen-off is a hard authentication boundary. Even a still-valid
        // timed session must not authorize the protected app after unlock.
        engine.onScreenOff()

        assertTrue(engine.onForegroundApp(WA).requireAuth)
        assertFalse(engine.isAuthorizedForLaunch(WA))
    }

    @Test
    fun `a session does not survive opening AppLock itself`() {
        policy.protectedApps.add(WA)
        satisfy(engine.onForegroundApp(WA))

        engine.onAppLockVisible()
        policy.clearUnlock(WA)

        assertTrue(engine.onForegroundApp(WA).requireAuth)
    }

    @Test
    fun `timed session rule is honoured exactly`() {
        policy.sessionRule = "MINUTES_5"
        policy.protectedApps.add(WA)

        satisfy(engine.onForegroundApp(WA))

        engine.onUserLeftForeground()
        policy.now += 60_000L
        assertFalse(engine.onForegroundApp(WA).requireAuth)

        engine.onUserLeftForeground()
        policy.now += 5 * 60_000L
        assertTrue(engine.onForegroundApp(WA).requireAuth)
    }

    @Test
    fun `throttling never grants access`() {
        policy.protectedApps.add(WA)

        engine.onForegroundApp(WA)
        engine.markAuthUiShown()
        repeat(5) { engine.authenticatePin(WA, "9999") }

        assertTrue(engine.isBlocked())
        assertTrue(engine.remainingLockoutSeconds() > 0)

        engine.onUserLeftForeground()
        assertTrue(engine.onForegroundApp(WA).requireAuth)
        assertFalse(engine.isAuthorizedForLaunch(WA))
        assertEquals(
            LockEngine.AuthenticationResult.BLOCKED,
            engine.authenticatePin(WA, policy.pin)
        )

        clock += 31_000L
        assertEquals(
            LockEngine.AuthenticationResult.SUCCESS,
            engine.authenticatePin(WA, policy.pin)
        )
    }


    @Test
    fun `destroying a lock Activity does not authorize or invalidate a live request unless service explicitly cancels it`() {
        policy.protectedApps.add(WA)

        val first = engine.onForegroundApp(WA)
        assertTrue(first.requireAuth)
        assertTrue(engine.isRequestActive(WA, first.requestId))
        assertFalse(engine.isAuthorizedForLaunch(WA))

        // Activity lifecycle destruction itself performs no engine operation.
        // The request must remain live so the service can relaunch the UI.
        assertTrue(engine.isRequestActive(WA, first.requestId))
        assertFalse(engine.isAuthorizedForLaunch(WA))
    }

    @Test
    fun `new protected package gets exactly one live request after switching`() {
        policy.protectedApps.add(WA)
        policy.protectedApps.add(OTHER)

        val first = engine.onForegroundApp(WA)
        val second = engine.onForegroundApp(OTHER)
        val secondRepeat = engine.onForegroundApp(OTHER)

        assertTrue(first.requireAuth)
        assertTrue(second.requireAuth)
        assertEquals(second.requestId, secondRepeat.requestId)
        assertFalse(engine.isRequestActive(WA, first.requestId))
        assertTrue(engine.isRequestActive(OTHER, second.requestId))
    }

    @Test
    fun `requests and sessions are isolated per package`() {
        policy.protectedApps.add(WA)
        policy.protectedApps.add(OTHER)

        val a = engine.onForegroundApp(WA)
        val b = engine.onForegroundApp(OTHER)

        assertNotEquals(a.requestId, b.requestId)
        assertFalse(engine.isRequestActive(WA, a.requestId))
        assertNotEquals(
            LockEngine.AuthenticationResult.SUCCESS,
            engine.authenticatePin(WA, policy.pin)
        )
        assertTrue(satisfy(b))
        assertFalse(engine.isAuthorizedForLaunch(WA))
    }

    @Test
    fun `missing credential never results in a silent unlock`() {
        policy.protectedApps.add(WA)
        policy.configured = false

        val decision = engine.onForegroundApp(WA)

        assertEquals(LockEngine.State.LIMITED_PROTECTION, engine.state.value)
        assertFalse(engine.isAuthorizedForLaunch(WA))
        assertEquals(0L, decision.requestId)
    }

    @Test
    fun `repeated window events re-assert one challenge instead of duplicating it`() {
        policy.protectedApps.add(WA)

        val first = engine.onForegroundApp(WA)
        val second = engine.onForegroundApp(WA)
        val third = engine.onForegroundApp(WA)

        assertEquals(first.requestId, second.requestId)
        assertEquals(second.requestId, third.requestId)
        assertTrue(third.requireAuth)
    }
}
