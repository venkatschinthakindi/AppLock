package applock.app.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import applock.app.AppLockApplication

object AntiTamperManager {

    private const val MANAGEMENT_GRANT_TTL_MS = 15_000L

    private const val PREFS = "anti_tamper"

    /*
     * Means that the AppLock explanation has already been presented
     * and the user has made a choice.
     *
     * This does NOT mean Device Admin is active.
     */
    private const val EXPLANATION_SHOWN =
        "device_admin_explanation_shown"

    @Volatile
    private var managementGrantPackage: String? = null

    @Volatile
    private var managementGrantUntilElapsed: Long = 0L

    fun component(context: Context): ComponentName =
        ComponentName(
            context,
            AppLockDeviceAdminReceiver::class.java
        )

    private fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    /**
     * Returns the actual Android OS Device Admin state.
     */
    fun isDeviceAdminActive(context: Context): Boolean =
        dpm(context)?.isAdminActive(component(context)) == true

    /**
     * Returns true when AppLock is configured as a Device Owner
     * or Profile Owner.
     *
     * Owner mode is stronger than ordinary Device Admin.
     */
    fun isStrongOwner(context: Context): Boolean {
        val manager = dpm(context) ?: return false

        return runCatching {
            manager.isDeviceOwnerApp(context.packageName) ||
                manager.isProfileOwnerApp(context.packageName)
        }.getOrDefault(false)
    }

    /**
     * Applies owner-level management restrictions when available.
     *
     * Ordinary Device Admin does not provide these owner-level APIs,
     * so this method safely does nothing unless AppLock is actually
     * a Device Owner or Profile Owner.
     */
    fun enforceStrongProtection(
        context: Context,
        protectedPackages: Set<String>
    ) {
        if (!isStrongOwner(context)) return

        val manager = dpm(context) ?: return
        val admin = component(context)

        val desired =
            protectedPackages
                .filter { it.isNotBlank() }
                .toSet()

        desired.forEach { packageName ->
            if (packageName != context.packageName) {
                runCatching {
                    manager.setUninstallBlocked(
                        admin,
                        packageName,
                        true
                    )
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                manager.setUserControlDisabledPackages(
                    admin,
                    desired.toList()
                )
            }
        }
    }

    /**
     * Determines whether the premium Device Admin explanation
     * should automatically appear as part of the initial security flow.
     *
     * Required state:
     *
     * - onboarding completed
     * - accessibility disclosure accepted
     * - authentication configured
     * - Device Admin not already active
     * - explanation has not already been handled
     */
    fun shouldShowDeviceAdminExplanation(
        context: Context
    ): Boolean {
        val app =
            context.applicationContext as? AppLockApplication
                ?: return false

        if (!app.repository.isOnboardingComplete()) {
            return false
        }

        if (!app.repository.disclosureAccepted()) {
            return false
        }

        if (!app.repository.authenticationConfigured()) {
            return false
        }

        if (isDeviceAdminActive(context)) {
            return false
        }

        return !isDeviceAdminExplanationShown(context)
    }

    /**
     * Returns whether the user has already been shown the
     * Device Admin explanation and made a choice.
     */
    fun isDeviceAdminExplanationShown(
        context: Context
    ): Boolean {
        return context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .getBoolean(
                EXPLANATION_SHOWN,
                false
            )
    }

    /**
     * Marks the explanation as handled.
     *
     * This is deliberately independent from actual Device Admin state.
     */
    fun markDeviceAdminExplanationShown(
        context: Context
    ) {
        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putBoolean(
                EXPLANATION_SHOWN,
                true
            )
            .apply()
    }

    /**
     * Opens Android's official Device Admin activation screen.
     *
     * AppLock does not reproduce or replace Android's system UI.
     *
     * Returns true if the Intent was successfully started.
     */
    fun requestDeviceAdmin(
        activity: Activity
    ): Boolean {
        return runCatching {
            activity.startActivity(
                Intent(
                    DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN
                ).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        component(activity)
                    )

                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Enable AppLock device protection to add an extra OS-level layer against app-management and tamper changes."
                    )
                }
            )

            true
        }.getOrDefault(false)
    }

    /**
     * Compatibility method retained for existing callers.
     *
     * It intentionally NEVER launches Android's Device Admin UI.
     *
     * The premium explanation screen owns the user-facing flow.
     */
    fun maybeRequestDeviceAdmin(
        activity: Activity
    ) {
        /*
         * Intentionally no automatic Device Admin launch.
         *
         * Existing callers remain safe because this method is now
         * a compatibility no-op.
         */
    }

    /**
     * Grants a short-lived management authorization.
     */
    @Synchronized
    fun grantManagementAccess(
        packageName: String
    ) {
        if (packageName.isBlank()) return

        managementGrantPackage = packageName

        managementGrantUntilElapsed =
            SystemClock.elapsedRealtime() +
                MANAGEMENT_GRANT_TTL_MS
    }

    /**
     * Consumes a previously granted short-lived authorization.
     */
    @Synchronized
    fun consumeManagementAccess(
        packageName: String
    ): Boolean {
        val valid =
            managementGrantPackage == packageName &&
                SystemClock.elapsedRealtime() <=
                managementGrantUntilElapsed

        if (valid) {
            clearManagementAccess()
        }

        return valid
    }

    @Synchronized
    fun clearManagementAccess() {
        managementGrantPackage = null
        managementGrantUntilElapsed = 0L
    }

    /**
     * Opens Android Accessibility settings.
     */
    fun openAccessibilitySettings(
        context: Context
    ) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    /**
     * Clears the one-time explanation state.
     *
     * This is useful for security-flow resets and also allows
     * a deliberate re-entry into the explanation flow.
     */
    fun clearPromptState(
        context: Context
    ) {
        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .remove(EXPLANATION_SHOWN)
            .apply()
    }
}