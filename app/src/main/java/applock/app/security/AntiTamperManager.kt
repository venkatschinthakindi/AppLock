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
    private const val PROMPT_SHOWN = "device_admin_prompt_shown"

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
     * True when AppLock's Device Administrator is actually active.
     *
     * This is the source of truth for the Device Admin protection state.
     */
    fun isDeviceAdminActive(context: Context): Boolean =
        dpm(context)?.isAdminActive(component(context)) == true

    /**
     * True only when AppLock is installed as a Device Owner or Profile Owner.
     *
     * Ordinary Device Admin does NOT provide these stronger DPM capabilities.
     */
    fun isStrongOwner(context: Context): Boolean {
        val manager = dpm(context) ?: return false

        return runCatching {
            manager.isDeviceOwnerApp(context.packageName) ||
                manager.isProfileOwnerApp(context.packageName)
        }.getOrDefault(false)
    }

    /**
     * Apply the strongest DPM protection available when AppLock is
     * actually a Device Owner/Profile Owner.
     */
    fun enforceStrongProtection(
        context: Context,
        protectedPackages: Set<String>
    ) {
        if (!isStrongOwner(context)) return

        val manager = dpm(context) ?: return
        val admin = component(context)

        // AppLock protects itself as well as the user's protected apps.
        val desired = (
            protectedPackages +
                context.packageName
            )
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
     * Automatically request Device Admin once after security setup.
     *
     * IMPORTANT:
     * PROMPT_SHOWN only prevents repeatedly launching the Android
     * Device Admin screen. It does NOT mean protection is enabled.
     *
     * The actual protection state is always determined by
     * isDeviceAdminActive().
     */
    fun maybeRequestDeviceAdmin(activity: Activity) {
        // val app = activity.application as AppLockApplication

        // Don't request Device Admin before the user has configured
        // an AppLock authentication method.
        // if (!app.repository.authenticationConfigured()) return

        // Already enabled: nothing to do.
        if (isDeviceAdminActive(activity)) return

        val prefs = activity.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        // The user has already seen the Android activation screen.
        // Do not repeatedly launch Settings.
        //
        // ProtectionHealthScreen / Settings will continue showing
        // the persistent warning and the Enable button.
        if (prefs.getBoolean(PROMPT_SHOWN, false)) return

        prefs.edit()
            .putBoolean(PROMPT_SHOWN, true)
            .apply()

        requestDeviceAdmin(activity)
    }

    /**
     * Explicit user action.
     *
     * This can be called repeatedly from Protection Health or Settings
     * until Device Admin is actually enabled.
     */
    fun requestDeviceAdmin(activity: Activity) {
        runCatching {
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
                        "Enable AppLock device protection to strengthen " +
                            "uninstall and app-management tamper protection."
                    )
                }
            )
        }
    }

    @Synchronized
    fun grantManagementAccess(packageName: String) {
        if (packageName.isBlank()) return

        managementGrantPackage = packageName
        managementGrantUntilElapsed =
            SystemClock.elapsedRealtime() +
                MANAGEMENT_GRANT_TTL_MS
    }

    @Synchronized
    fun consumeManagementAccess(packageName: String): Boolean {
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

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    /**
     * Allows the automatic Device Admin request to become available again.
     *
     * This is used when Device Admin has actually been disabled.
     */
    fun clearPromptState(context: Context) {
        context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )
            .edit()
            .remove(PROMPT_SHOWN)
            .apply()
    }
}