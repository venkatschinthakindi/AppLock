package applock.app.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import applock.app.AppLockApplication

object AntiTamperManager {
    private const val MANAGEMENT_GRANT_TTL_MS = 15_000L
    @Volatile private var managementGrantPackage: String? = null
    @Volatile private var managementGrantUntilElapsed: Long = 0L
    private const val PREFS = "anti_tamper"
    private const val PROMPT_SHOWN = "device_admin_prompt_shown"

    fun component(context: Context): ComponentName =
        ComponentName(context, AppLockDeviceAdminReceiver::class.java)

    fun isDeviceAdminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            ?.isAdminActive(component(context)) == true

    fun maybeRequestDeviceAdmin(activity: Activity) {
        val app = activity.application as AppLockApplication
        if (!app.repository.authenticationConfigured()) return
        if (isDeviceAdminActive(activity)) return

        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PROMPT_SHOWN, false)) return

        prefs.edit().putBoolean(PROMPT_SHOWN, true).apply()
        requestDeviceAdmin(activity)
    }

    fun requestDeviceAdmin(activity: Activity) {
        runCatching {
            activity.startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(activity))
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Enable App Lock device protection to make uninstall, force-stop and tamper attempts harder to bypass."
                    )
                }
            )
        }
    }

    @Synchronized
    fun grantManagementAccess(packageName: String) {
        if (packageName.isBlank()) return
        managementGrantPackage = packageName
        managementGrantUntilElapsed = android.os.SystemClock.elapsedRealtime() + MANAGEMENT_GRANT_TTL_MS
    }

    @Synchronized
    fun consumeManagementAccess(packageName: String): Boolean {
        val valid = managementGrantPackage == packageName &&
            android.os.SystemClock.elapsedRealtime() <= managementGrantUntilElapsed
        if (valid) {
            managementGrantPackage = null
            managementGrantUntilElapsed = 0L
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
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    fun clearPromptState(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(PROMPT_SHOWN)
            .apply()
    }
}
