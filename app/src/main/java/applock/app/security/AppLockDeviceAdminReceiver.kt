package applock.app.security

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AppLockDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        // No user credential is persisted or changed here. Device Admin is an
        // additional OS-level hardening signal; the AppLock credential remains
        // the gate for security-sensitive management UI.
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // The OS has already disabled the admin. The remaining anti-tamper
        // protection is the accessibility management-surface gate.
    }

    override fun onDisableRequested(
        context: Context,
        intent: Intent
    ): CharSequence {
        return "Disabling App Lock device protection reduces uninstall and tamper protection. Authenticate in App Lock before changing security settings."
    }
}
