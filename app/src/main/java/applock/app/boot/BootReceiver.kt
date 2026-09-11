package applock.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import applock.app.AppLockApplication

/**
 * Re-establishes a conservative security baseline after a real device reboot.
 * The receiver never starts the accessibility service; Android owns that lifecycle.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val app = context.applicationContext as AppLockApplication
        app.repository.clearAllUnlocks()
        app.lockEngine.reset()
        app.repository.refreshProtectionState()
    }
}
