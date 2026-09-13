package applock.app.security

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class AppLockDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onDisableRequested(
        context: Context,
        intent: Intent
    ): CharSequence {
        return "Disabling App Lock device protection reduces uninstall and tamper protection. Authenticate in App Lock before changing security settings."
    }
}
