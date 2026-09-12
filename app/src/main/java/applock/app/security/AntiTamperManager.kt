package applock.app.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import applock.app.R

/**
 * Optional Android Device Administrator hardening.
 *
 * When active, Android protects the app from normal uninstall until the
 * administrator is removed. This is an Android platform safeguard, not a
 * replacement for AppLock authentication.
 *
 * Device Administrator cannot provide absolute protection against device-owner
 * actions, factory reset, recovery/ADB abuse, or every OEM-specific management
 * path. The app therefore continues to treat Accessibility as the actual
 * foreground interception mechanism.
 */
object AntiTamperManager {

    private const val PREFS = "anti_tamper"
    private const val KEY_PROMPT_SHOWN = "device_admin_prompt_shown"

    fun component(activity: Activity): ComponentName =
        ComponentName(
            activity,
            AppLockDeviceAdminReceiver::class.java
        )

    fun isDeviceAdminActive(activity: Activity): Boolean {
        val manager =
            activity.getSystemService(DevicePolicyManager::class.java)
                ?: return false

        return manager.isAdminActive(component(activity))
    }

    fun maybeRequestDeviceAdmin(activity: Activity) {
        if (isDeviceAdminActive(activity)) {
            return
        }

        val prefs =
            activity.getSharedPreferences(
                PREFS,
                Activity.MODE_PRIVATE
            )

        /*
         * Ask once automatically after the user has configured real
         * protection. The user can still decline and continue using AppLock.
         */
        if (prefs.getBoolean(KEY_PROMPT_SHOWN, false)) {
            return
        }

        prefs.edit()
            .putBoolean(KEY_PROMPT_SHOWN, true)
            .apply()

        requestDeviceAdmin(activity)
    }

    fun requestDeviceAdmin(activity: Activity) {
        val intent =
            Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    component(activity)
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    activity.getString(
                        R.string.device_admin_explanation
                    )
                )
            }

        activity.startActivity(intent)
    }

    fun clearPromptState(activity: Activity) {
        activity.getSharedPreferences(
            PREFS,
            Activity.MODE_PRIVATE
        ).edit()
            .remove(KEY_PROMPT_SHOWN)
            .apply()
    }
}
