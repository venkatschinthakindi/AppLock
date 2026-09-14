package applock.app.ui

import android.content.Context

/**
 * Persists first-run decisions that are not themselves security
 * credentials.
 *
 * Device Admin itself is always verified through Android's live
 * DevicePolicyManager state. This flag only records that the user
 * has explicitly made a first-run decision:
 *
 *  - enabled Device Admin
 *  - or deliberately chose "Not now"
 */
object FirstRunSetupState {

    private const val PREFS_NAME =
        "app_lock_first_run"

    private const val KEY_DEVICE_ADMIN_DECISION =
        "device_admin_decision_complete"

    private fun preferences(
        context: Context
    ) =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun isDeviceAdminDecisionComplete(
        context: Context
    ): Boolean =
        preferences(context).getBoolean(
            KEY_DEVICE_ADMIN_DECISION,
            false
        )

    fun markDeviceAdminDecisionComplete(
        context: Context
    ) {
        preferences(context)
            .edit()
            .putBoolean(
                KEY_DEVICE_ADMIN_DECISION,
                true
            )
            .apply()
    }

    /**
     * Useful for development/manual testing.
     *
     * This intentionally does not disable Android Device Admin.
     * It only causes AppLock's own first-run flow to offer the
     * Device Admin decision again.
     */
    fun resetDeviceAdminDecision(
        context: Context
    ) {
        preferences(context)
            .edit()
            .remove(
                KEY_DEVICE_ADMIN_DECISION
            )
            .apply()
    }
}