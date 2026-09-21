
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

class AppLockRepository(
    private val context: Context
) : applock.app.engine.LockPolicySource {

    private val prefs = context.getSharedPreferences(
        "app_lock_settings",
        Context.MODE_PRIVATE
    )

    private val secure = SecureStorage(context)

    /*
     * Cache the protected package list before constructing any state that
     * depends on it.
     *
     * IMPORTANT:
     * Kotlin initializes properties from top to bottom. The protection
     * snapshot below calls computeProtectionSnapshot(), which reads this
     * cache. Therefore this cache MUST be initialized first.
     */
    @Volatile
    private var protectedPackagesCache: Set<String> =
        loadProtectedPackages()

    /*
     * Cache the expensive-ish Android AccessibilityManager check.
     *
     * The value is refreshed whenever the application explicitly refreshes
     * its protection state, including accessibility state changes.
     */
    @Volatile
    private var accessibilityEnabledCache: Boolean = false

    private val _theme = MutableStateFlow(loadTheme())
    val theme: StateFlow<ThemeSettings> = _theme.asStateFlow()

    private var forceStopRecoveryThisProcess = false

    /*
     * This MUST be initialized after protectedPackagesCache and
     * accessibilityEnabledCache.
     */
    private val _protectionSnapshot =
        MutableStateFlow(computeProtectionSnapshot())

    val protectionSnapshot: StateFlow<ProtectionSnapshot> =
        _protectionSnapshot.asStateFlow()

    private fun loadProtectedPackages(): Set<String> {
        return runCatching {
            prefs.getStringSet(
                "protected_packages",
                emptySet()
            )?.toSet() ?: emptySet()
        }.getOrDefault(emptySet())
    }

    private fun loadTheme() = ThemeSettings(
        mode = runCatching {
            ThemeMode.valueOf(
                prefs.getString(
                    "theme_mode",
                    ThemeMode.SYSTEM.name
                ) ?: ThemeMode.SYSTEM.name
            )
        }.getOrDefault(ThemeMode.SYSTEM),

        accent = prefs.getLong(
            "accent",
            0xFF6C63FF
        ),

        cornerRadius = prefs.getFloat(
            "corner_radius",
            20f
        ),

        animationScale = prefs.getFloat(
            "animation_scale",
            1f
        ),

        animationStyle = runCatching {
            AnimationStyle.valueOf(
                prefs.getString(
                    "animation_style",
                    AnimationStyle.COSMIC_ORB.name
                ) ?: AnimationStyle.COSMIC_ORB.name
            )
        }.getOrDefault(AnimationStyle.COSMIC_ORB),

        reducedMotion = prefs.getBoolean(
            "reduced_motion",
            false
        )
    )

    fun updateTheme(
        transform: (ThemeSettings) -> ThemeSettings
    ) {
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

    fun isOnboardingComplete() =
        prefs.getBoolean("onboarding_complete", false)

    fun setOnboardingComplete(value: Boolean) =
        prefs.edit()
            .putBoolean("onboarding_complete", value)
            .apply()

    fun disclosureAccepted() =
        prefs.getBoolean(
            "accessibility_disclosure_accepted",
            false
        )

    fun setDisclosureAccepted(value: Boolean) =
        prefs.edit()
            .putBoolean(
                "accessibility_disclosure_accepted",
                value
            )
            .apply()

    override fun getAuthMethod(): AuthMethod =
        runCatching {
            AuthMethod.valueOf(
                prefs.getString(
                    "auth_method",
                    AuthMethod.PIN.name
                ) ?: AuthMethod.PIN.name
            )
        }.getOrDefault(AuthMethod.PIN)

    fun setAuthMethod(value: AuthMethod) {
        when (value) {
            AuthMethod.PIN -> {
                secure.remove("pattern")
            }

            AuthMethod.PATTERN -> {
                secure.remove("pin")
            }

            AuthMethod.BIOMETRIC -> {
                /*
                 * Biometric uses PIN as its fallback credential.
                 */
                secure.remove("pattern")
            }
        }

        prefs.edit()
            .putString("auth_method", value.name)
            .apply()

        refreshProtectionState()
    }

    fun getSessionRule(): SessionRule =
        runCatching {
            SessionRule.valueOf(
                prefs.getString(
                    "session_rule",
                    SessionRule.IMMEDIATELY.name
                ) ?: SessionRule.IMMEDIATELY.name
            )
        }.getOrDefault(SessionRule.IMMEDIATELY)

    fun setSessionRule(value: SessionRule) {
        prefs.edit()
            .putString("session_rule", value.name)
            .apply()

        refreshProtectionState()
    }

    fun setPin(pin: String) {
        secure.write("pin", pin)
        refreshProtectionState()
    }

    override fun verifyPin(pin: String): Boolean =
        pin.length in 4..8 &&
            secure.read("pin") == pin

    override fun hasPin(): Boolean =
        secure.read("pin") != null

    fun setPattern(pattern: String) {
        secure.write("pattern", pattern)
        refreshProtectionState()
    }

    override fun verifyPattern(pattern: String): Boolean =
        secure.read("pattern") == pattern

    override fun hasPattern(): Boolean =
        secure.read("pattern") != null

    fun hasCredential(): Boolean =
        authenticationConfigured()

    override fun authenticationConfigured(): Boolean =
        when (getAuthMethod()) {
            AuthMethod.PIN -> hasPin()
            AuthMethod.PATTERN -> hasPattern()
            AuthMethod.BIOMETRIC -> hasPin()
        }

    fun protectedPackages(): Set<String> =
        protectedPackagesCache

    fun setProtected(
        packageName: String,
        enabled: Boolean
    ) {
        if (
            packageName.isBlank() ||
            packageName == context.packageName
        ) {
            return
        }

        val set = protectedPackagesCache
            .toMutableSet()

        if (enabled) {
            set.add(packageName)
        } else {
            set.remove(packageName)
        }

        val immutableSet = set.toSet()

        protectedPackagesCache = immutableSet

        prefs.edit()
            .putStringSet(
                "protected_packages",
                immutableSet
            )
            .apply()

        /*
         * Any change to the protection state of a package is a security
         * boundary: an old unlock timestamp must never survive it.
         *
         * Enabling protection previously kept a stale "unlock_<pkg>" value
         * from an earlier protected period, which let the very first launch
         * after configuration pass without a challenge under timed session
         * rules. Both directions now clear it.
         */
        prefs.edit()
            .remove("unlock_$packageName")
            .apply()

        refreshProtectionState()
    }

    override fun isProtected(packageName: String): Boolean =
        packageName.isNotBlank() &&
            protectedPackagesCache.contains(packageName)

    /**
     * Records successful authentication.
     *
     * This is intentionally the only point where an unlock timestamp is
     * written.
     */
    override fun markUnlocked(packageName: String): Boolean {
        if (
            packageName.isBlank() ||
            !isProtected(packageName)
        ) {
            return false
        }

        prefs.edit()
            .putLong(
                "unlock_$packageName",
                System.currentTimeMillis()
            )
            .apply()

        return true
    }

    fun clearUnlock(packageName: String) {
        if (packageName.isBlank()) {
            return
        }

        prefs.edit()
            .remove("unlock_$packageName")
            .apply()
    }

    /**
     * Unlock sessions never survive a device reboot/process cold start.
     */
    fun clearAllUnlocksIfNeededForColdStart() {
        clearAllUnlocks()
    }

    fun clearAllUnlocks() {
        val editor = prefs.edit()

        protectedPackagesCache.forEach { packageName ->
            editor.remove("unlock_$packageName")
        }

        editor.apply()
    }

    /**
     * Screen off / device lock.
     *
     * In-memory foreground authorization is always destroyed by the engine.
     * Persisted unlock timestamps are cleared for every rule whose semantics
     * end at a departure; explicit timed rules keep running as configured.
     */
    fun clearUnlocksForScreenOff() {
        // Screen-off is a hard security boundary for AppLock. A timestamp
        // created before the device was locked must never authorize a
        // protected app after the device becomes interactive again.
        clearAllUnlocks()
    }

    fun recentlyUnlockedAt(packageName: String): Long =
        prefs.getLong(
            "unlock_$packageName",
            0L
        )

    /**
     * Determines whether authentication is required.
     *
     * Session semantics:
     *
     * IMMEDIATELY:
     *   Every new entry into the protected application requires auth.
     *
     * AFTER_LEAVING:
     *   Unlock remains valid until the service detects that the user leaves
     *   the protected application.
     *
     * SCREEN_OFF:
     *   Unlock remains valid until screen-off clears all sessions.
     *
     * Timed sessions:
     *   Unlock remains valid for the selected duration.
     */
    override fun shouldRequireAuth(packageName: String): Boolean =
        shouldRequireAuth(packageName, System.currentTimeMillis())

    fun shouldRequireAuth(
        packageName: String,
        now: Long
    ): Boolean {

        if (!isProtected(packageName)) {
            return false
        }

        val last = recentlyUnlockedAt(packageName)

        if (last == 0L) {
            return true
        }

        /*
         * Fail closed if the wall clock moved backwards.
         */
        if (now < last) {
            return true
        }

        return when (getSessionRule()) {

            SessionRule.IMMEDIATELY,
            SessionRule.AFTER_LEAVING,
            SessionRule.SCREEN_OFF -> true

            SessionRule.MINUTES_1 ->
                now - last >= 60_000L

            SessionRule.MINUTES_5 ->
                now - last >= 300_000L

            SessionRule.MINUTES_15 ->
                now - last >= 900_000L

            SessionRule.MINUTES_30 ->
                now - last >= 1_800_000L
        }
    }

    fun launchableApps(): List<ProtectedApp> {
        val pm = context.packageManager

        val intent = Intent(
            Intent.ACTION_MAIN
        ).addCategory(
            Intent.CATEGORY_LAUNCHER
        )

        return pm.queryIntentActivities(
            intent,
            PackageManager.MATCH_ALL
        )
            .asSequence()
            .map {
                it.activityInfo.packageName
            }
            .distinct()
            .filter {
                it != context.packageName
            }
            .map { packageName ->

                val label = runCatching {
                    pm.getApplicationLabel(
                        pm.getApplicationInfo(
                            packageName,
                            0
                        )
                    ).toString()
                }.getOrDefault(packageName)

                ProtectedApp(
                    packageName,
                    label,
                    isProtected(packageName)
                )
            }
            .sortedBy {
                it.label.lowercase()
            }
            .toList()
    }

    /**
     * Reads Android's live accessibility-service registry.
     *
     * The result is cached so the foreground-app event path doesn't repeatedly
     * query AccessibilityManager.
     */
    fun accessibilityEnabled(): Boolean =
        accessibilityEnabledCache

    /**
     * Explicitly refreshes the live Android accessibility state.
     */
    private fun queryAccessibilityEnabled(): Boolean {
        val manager =
            context.getSystemService(
                AccessibilityManager::class.java
            ) ?: return false

        return runCatching {
            manager
                .getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
                .any { info ->

                    val serviceInfo =
                        info.resolveInfo?.serviceInfo
                            ?: return@any false

                    serviceInfo.packageName ==
                        context.packageName &&
                        serviceInfo.name ==
                        "applock.app.service.AppDetectionAccessibilityService"
                }

        }.getOrDefault(false)
    }

    fun markForceStopRecovery() {
        forceStopRecoveryThisProcess = true

        clearAllUnlocks()

        refreshProtectionState()
    }

    fun forceStopRecoveryPending(): Boolean =
        forceStopRecoveryThisProcess

    /**
     * Recalculate protection from live Android state and local security
     * configuration.
     */
    fun refreshProtectionState(): ProtectionSnapshot {

        accessibilityEnabledCache =
            queryAccessibilityEnabled()

        /*
         * Refresh the package cache as settings can be changed from UI code.
         */
        protectedPackagesCache =
            loadProtectedPackages()

        val snapshot =
            computeProtectionSnapshot()

        _protectionSnapshot.value =
            snapshot

        return snapshot
    }

    private fun computeProtectionSnapshot(): ProtectionSnapshot {

        val credential =
            authenticationConfigured()

        val protectedCount =
            protectedPackagesCache.size

        val accessibility =
            accessibilityEnabledCache

        val state =
            when {
                !credential ->
                    HealthState.RED

                protectedCount == 0 ->
                    HealthState.YELLOW

                !accessibility ->
                    HealthState.RED

                else ->
                    HealthState.GREEN
            }

        val reason =
            when {
                !credential ->
                    ProtectionReason.SECURITY_SETUP_REQUIRED

                protectedCount == 0 ->
                    ProtectionReason.NO_PROTECTED_APPS

                !accessibility ->
                    ProtectionReason.ACCESSIBILITY_DISABLED

                else ->
                    ProtectionReason.PROTECTED
            }

        return ProtectionSnapshot(
            state = state,
            reason = reason,
            accessibilityEnabled = accessibility,
            credentialConfigured = credential,
            protectedAppCount = protectedCount,
            forceStopRecoveryPending =
                forceStopRecoveryPending()
        )
    }
}
