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
        mode = runCatching {
            ThemeMode.valueOf(prefs.getString("theme_mode", ThemeMode.SYSTEM.name)!!)
        }.getOrDefault(ThemeMode.SYSTEM),
        accent = prefs.getLong("accent", 0xFF6C63FF),
        cornerRadius = prefs.getFloat("corner_radius", 20f),
        animationScale = prefs.getFloat("animation_scale", 1f),
        animationStyle = runCatching {
            AnimationStyle.valueOf(
                prefs.getString("animation_style", AnimationStyle.COSMIC_ORB.name)!!
            )
        }.getOrDefault(AnimationStyle.COSMIC_ORB),
        reducedMotion = prefs.getBoolean("reduced_motion", false)
    )

    fun updateTheme(transform: (ThemeSettings) -> ThemeSettings) {
        val next = transform(_theme.value)
        prefs.edit()
            .putString("theme_mode", next.mode.name)
            .putLong("accent", next.accent)
            .putFloat("corner_radius", next.cornerRadius.coerceIn(10f, 32f))
            .putFloat("animation_scale", next.animationScale.coerceIn(0f, 1f))
            .putString("animation_style", next.animationStyle.name)
            .putBoolean("reduced_motion", next.reducedMotion)
            .apply()
        _theme.value = next.copy(
            cornerRadius = next.cornerRadius.coerceIn(10f, 32f),
            animationScale = next.animationScale.coerceIn(0f, 1f)
        )
    }

    fun isOnboardingComplete() = prefs.getBoolean("onboarding_complete", false)
    fun setOnboardingComplete(v: Boolean) = prefs.edit().putBoolean("onboarding_complete", v).apply()
    fun disclosureAccepted() = prefs.getBoolean("accessibility_disclosure_accepted", false)
    fun setDisclosureAccepted(v: Boolean) = prefs.edit().putBoolean("accessibility_disclosure_accepted", v).apply()

    fun getAuthMethod() = runCatching {
        AuthMethod.valueOf(prefs.getString("auth_method", AuthMethod.PIN.name)!!)
    }.getOrDefault(AuthMethod.PIN)

    /** Authentication configuration changes must be authorized by the current credential in UI/engine. */
    fun setAuthMethod(v: AuthMethod) {
        when (v) {
            AuthMethod.PIN -> secure.remove("pattern")
            AuthMethod.PATTERN -> secure.remove("pin")
            AuthMethod.BIOMETRIC -> secure.remove("pattern")
        }
        prefs.edit().putString("auth_method", v.name).apply()
        refreshProtectionState()
    }

    fun getSessionRule() = runCatching {
        SessionRule.valueOf(
            prefs.getString("session_rule", SessionRule.IMMEDIATELY.name)!!
        )
    }.getOrDefault(SessionRule.IMMEDIATELY)

    fun setSessionRule(v: SessionRule) {
        prefs.edit().putString("session_rule", v.name).apply()
        refreshProtectionState()
    }

    fun setPin(pin: String) {
        require(pin.length in 4..8 && pin.all(Char::isDigit)) { "PIN must contain 4-8 digits" }
        secure.write("pin", pin)
        refreshProtectionState()
    }

    fun verifyPin(pin: String): Boolean = runCatching {
        pin.length in 4..8 && pin.all(Char::isDigit) && secure.read("pin") == pin
    }.getOrDefault(false)

    fun hasPin(): Boolean = runCatching { secure.read("pin") != null }.getOrDefault(false)

    fun setPattern(pattern: String) {
        require(pattern.isNotBlank()) { "Pattern must not be blank" }
        secure.write("pattern", pattern)
        refreshProtectionState()
    }

    fun verifyPattern(pattern: String): Boolean = runCatching {
        pattern.isNotBlank() && secure.read("pattern") == pattern
    }.getOrDefault(false)

    fun hasPattern(): Boolean = runCatching { secure.read("pattern") != null }.getOrDefault(false)

    fun hasCredential(): Boolean = authenticationConfigured()

    fun authenticationConfigured(): Boolean = when (getAuthMethod()) {
        AuthMethod.PIN -> hasPin()
        AuthMethod.PATTERN -> hasPattern()
        // Biometric mode always has a PIN recovery credential.
        AuthMethod.BIOMETRIC -> hasPin()
    }

    /** True only when the secure store itself is readable enough to make a security decision. */
    fun secureStorageHealthy(): Boolean {
        return runCatching {
            val method = getAuthMethod()
            when (method) {
                AuthMethod.PIN, AuthMethod.BIOMETRIC -> {
                    val value = secure.read("pin")
                    value == null || (value.length in 4..8 && value.all(Char::isDigit))
                }
                AuthMethod.PATTERN -> {
                    val value = secure.read("pattern")
                    value == null || value.isNotBlank()
                }
            }
        }.getOrDefault(false)
    }

    fun protectedPackages(): Set<String> =
        prefs.getStringSet("protected_packages", emptySet())?.toSet() ?: emptySet()

    fun setProtected(packageName: String, enabled: Boolean) {
        if (packageName.isBlank() || packageName == context.packageName) return
        val set = protectedPackages().toMutableSet()
        if (enabled) set.add(packageName) else set.remove(packageName)
        prefs.edit().putStringSet("protected_packages", set).apply()
        refreshProtectionState()
    }

    fun isProtected(packageName: String) = protectedPackages().contains(packageName)

    fun markUnlocked(packageName: String): Boolean {
        if (packageName.isBlank() || !isProtected(packageName)) return false
        prefs.edit()
            .putLong("unlock_$packageName", System.currentTimeMillis())
            .apply()
        return true
    }

    fun clearUnlock(packageName: String) {
        if (packageName.isBlank()) return
        prefs.edit().remove("unlock_$packageName").apply()
    }

    /** Unlock sessions never survive a device/process restart. */
    fun clearAllUnlocksIfNeededForColdStart() = clearAllUnlocks()

    fun clearAllUnlocks() {
        val editor = prefs.edit()
        protectedPackages().forEach { editor.remove("unlock_$it") }
        editor.apply()
    }

    fun recentlyUnlockedAt(packageName: String) = prefs.getLong("unlock_$packageName", 0L)

    fun shouldRequireAuth(
        packageName: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (!isProtected(packageName)) return false
        val last = recentlyUnlockedAt(packageName)
        if (last == 0L) return true
        if (now < last) return true

        return when (getSessionRule()) {
            SessionRule.IMMEDIATELY,
            SessionRule.AFTER_LEAVING,
            SessionRule.SCREEN_OFF -> true
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
            .map { pkg ->
                ProtectedApp(
                    packageName = pkg,
                    label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg),
                    protected = isProtected(pkg)
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

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

    fun refreshProtectionState(): ProtectionSnapshot {
        val snapshot = computeProtectionSnapshot()
        _protectionSnapshot.value = snapshot
        return snapshot
    }

    private fun computeProtectionSnapshot(): ProtectionSnapshot {
        val storageHealthy = secureStorageHealthy()
        val credential = storageHealthy && authenticationConfigured()
        val protectedCount = protectedPackages().size
        val accessibility = accessibilityEnabled()

        val state = when {
            !storageHealthy -> HealthState.RED
            !credential -> HealthState.RED
            protectedCount == 0 -> HealthState.YELLOW
            !accessibility -> HealthState.RED
            else -> HealthState.GREEN
        }
        val reason = when {
            !storageHealthy -> ProtectionReason.SECURE_STORAGE_ERROR
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
            forceStopRecoveryPending = forceStopRecoveryPending(),
            secureStorageHealthy = storageHealthy
        )
    }
}
