package applock.app.security

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Device Administrator used as an anti-uninstall hardening layer.
 *
 * Android requires an active device administrator to be removed before the
 * administrator application can normally be uninstalled.
 */
class AppLockDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(
        context: Context,
        intent: Intent
    ) {
        super.onEnabled(context, intent)
    }

    override fun onDisableRequested(
        context: Context,
        intent: Intent
    ): CharSequence {
        return "Disabling AppLock protection removes the anti-uninstall safeguard. Your protected apps may no longer be secured."
    }

    override fun onDisabled(
        context: Context,
        intent: Intent
    ) {
        super.onDisabled(context, intent)
    }
}
