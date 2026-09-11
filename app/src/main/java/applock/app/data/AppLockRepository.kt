package applock.app.data

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityManager
import applock.app.domain.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppLockRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("app_lock_settings", Context.MODE_PRIVATE)
    private val secure = SecureStorage(context)
    private val _theme = MutableStateFlow(loadTheme())
    val theme: StateFlow<ThemeSettings> = _theme.asStateFlow()

    private var forceStopRecoveryThisProcess = false

    private val _protectionSnapshot = MutableStateFlow(computeProtectionSnapshot())
    val protectionSnapshot: StateFlow<ProtectionSnapshot> = _protectionSnapshot.asStateFlow()

    private fun loadTheme() = ThemeSettings(
        mode = runCatching { ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name)!!) }.getOrDefault(ThemeMode.SYSTEM),
        accent = prefs.getLong("accent", 0xFF6C63FF),
        cornerRadius = prefs.getFloat("corner_radius", 20f),
        animationScale = prefs.getFloat("animation_scale", 1f),
        animationStyle = runCatching { AnimationStyle.valueOf(prefs.getString("animation_style", AnimationStyle.COSMIC_ORB.name)!!) }.getOrDefault(AnimationStyle.COSMIC_ORB),
        reducedMotion = prefs.getBoolean("reduced_motion", false)
    )

    fun updateTheme(transform: (ThemeSettings) -> ThemeSettings) {
        val next = transform(_theme.value)
        prefs.edit()
            .putString("theme_mode", next.mode.name)
            .putLong("accent", next.accent)
            .putFloat("corner_radius", next.cornerRadius)
            .putFloat("animation_scale", next.animationScale)
            .putString("animation_style", next.animationStyle.name)
            .putBoolean("reduced_motion", next.reducedMotion)
            .apply()
        _theme.value = next
    }

    fun isOnboardingComplete() = prefs.getBoolean("onboarding_complete", false)
    fun setOnboardingComplete(v: Boolean) = prefs.edit().putBoolean("onboarding_complete", v).apply()
    fun disclosureAccepted() = prefs.getBoolean("accessibility_disclosure_accepted", false)
    fun setDisclosureAccepted(v: Boolean) = prefs.edit().putBoolean("accessibility_disclosure_accepted", v).apply()

    fun getAuthMethod() = runCatching {
        AuthMethod.valueOf(prefs.getString("auth_method", AuthMethod.PIN.name)!!)
    }.getOrDefault(AuthMethod.PIN)

    /** Changes to authentication configuration are performed only after the UI has authenticated the current credential. */
    fun setAuthMethod(v: AuthMethod) {
        prefs.edit().putString("auth_method", v.name).apply()
        refreshProtectionState()
    }
    fun getSessionRule() = runCatching { SessionRule.valueOf(prefs.getString("session_rule", SessionRule.IMMEDIATELY.name)!!) }.getOrDefault(SessionRule.IMMEDIATELY)
    fun setSessionRule(v: SessionRule) {
        prefs.edit().putString("session_rule", v.name).apply()
        refreshProtectionState()
    }

    fun setPin(pin: String) {
        secure.write("pin", pin)
        refreshProtectionState()
    }
    fun verifyPin(pin: String) = pin.length in 4..8 && secure.read("pin") == pin
    fun hasPin() = secure.read("pin") != null
    fun setPattern(pattern: String) {
        secure.write("pattern", pattern)
        refreshProtectionState()
    }
    fun verifyPattern(pattern: String) = secure.read("pattern") == pattern
    fun hasPattern() = secure.read("pattern") != null
    fun hasCredential(): Boolean = authenticationConfigured()

    fun authenticationConfigured(): Boolean = when (getAuthMethod()) {
        AuthMethod.PIN -> hasPin()
        AuthMethod.PATTERN -> hasPattern()
        AuthMethod.BIOMETRIC -> hasPin()
    }

    fun protectedPackages(): Set<String> = prefs.getStringSet("protected_packages", emptySet())?.toSet() ?: emptySet()
    fun setProtected(packageName: String, enabled: Boolean) {
        val set = protectedPackages().toMutableSet()
        if (enabled) set.add(packageName) else set.remove(packageName)
        prefs.edit().putStringSet("protected_packages", set).apply()
        refreshProtectionState()
    }
    fun isProtected(packageName: String) = protectedPackages().contains(packageName)

    fun markUnlocked(packageName: String) = prefs.edit().putLong("unlock_$packageName", System.currentTimeMillis()).apply()
    fun clearUnlock(packageName: String) = prefs.edit().remove("unlock_$packageName").apply()

    /** Unlock sessions never survive a device reboot. */
    /** A process restart must not turn a previous in-memory authentication into a new session. */
    fun clearAllUnlocksIfNeededForColdStart() {
        clearAllUnlocks()
    }

    fun clearAllUnlocks() {
        val editor = prefs.edit()
        protectedPackages().forEach { editor.remove("unlock_$it") }
        editor.apply()
    }

    fun recentlyUnlockedAt(packageName: String) = prefs.getLong("unlock_$packageName", 0L)

    fun shouldRequireAuth(packageName: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!isProtected(packageName)) return false
        val last = recentlyUnlockedAt(packageName)
        if (last == 0L) return true
        return when (getSessionRule()) {
            SessionRule.IMMEDIATELY, SessionRule.AFTER_LEAVING, SessionRule.SCREEN_OFF -> true
            SessionRule.MINUTES_1 -> now - last >= 60_000L
            SessionRule.MINUTES_5 -> now - last >= 300_000L
            SessionRule.MINUTES_15 -> now - last >= 900_000L
            SessionRule.MINUTES_30 -> now - last >= 1_800_000L
        }
    }

    fun launchableApps(): List<ProtectedApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .asSequence()
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { pkg -> ProtectedApp(pkg, runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg), isProtected(pkg)) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /**
     * Reads Android's actual enabled accessibility-service registry. It does not rely on
     * a local preference because the user can change the service state in Android Settings.
     */
    fun accessibilityEnabled(): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    serviceInfo.packageName == context.packageName &&
                        serviceInfo.name == "applock.app.service.AppDetectionAccessibilityService"
                }
        }.getOrDefault(false)
    }

    fun markForceStopRecovery() {
        forceStopRecoveryThisProcess = true
        clearAllUnlocks()
        refreshProtectionState()
    }

    fun forceStopRecoveryPending(): Boolean = forceStopRecoveryThisProcess

    /** Recalculate protection from live Android state and local security configuration. */
    fun refreshProtectionState(): ProtectionSnapshot {
        val snapshot = computeProtectionSnapshot()
        _protectionSnapshot.value = snapshot
        return snapshot
    }

    private fun computeProtectionSnapshot(): ProtectionSnapshot {
        val credential = authenticationConfigured()
        val protectedCount = protectedPackages().size
        val accessibility = accessibilityEnabled()

        val state = when {
            !credential -> HealthState.RED
            protectedCount == 0 -> HealthState.YELLOW
            !accessibility -> HealthState.RED
            else -> HealthState.GREEN
        }

        val reason = when {
            !credential -> ProtectionReason.SECURITY_SETUP_REQUIRED
            protectedCount == 0 -> ProtectionReason.NO_PROTECTED_APPS
            !accessibility -> ProtectionReason.ACCESSIBILITY_DISABLED
            else -> ProtectionReason.PROTECTED
        }

        return ProtectionSnapshot(
            state = state,
            reason = reason,
            accessibilityEnabled = accessibility,
            credentialConfigured = credential,
            protectedAppCount = protectedCount,
            forceStopRecoveryPending = forceStopRecoveryPending()
        )
    }
}
