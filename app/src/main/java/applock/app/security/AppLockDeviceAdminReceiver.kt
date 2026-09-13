package applock.app.security

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AppLockDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(
        context: Context,
        intent: Intent
    ) {
        super.onEnabled(context, intent)

        // Device Admin is now active.
        //
        // AppLock authentication remains completely independent
        // of Device Admin.
    }

    override fun onDisabled(
        context: Context,
        intent: Intent
    ) {
        super.onDisabled(context, intent)

        // The user has removed Device Admin.
        //
        // Allow AppLock to offer activation again.
        AntiTamperManager.clearPromptState(context)
    }

    override fun onDisableRequested(
        context: Context,
        intent: Intent
    ): CharSequence {
        return "Disabling AppLock device protection reduces uninstall " +
            "and tamper protection. Authenticate in AppLock before " +
            "changing security settings."
    }
}