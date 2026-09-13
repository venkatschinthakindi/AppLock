package applock.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import applock.app.AppLockApplication

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as AppLockApplication
        app.repository.clearAllUnlocks()
        app.lockEngine.resetTransitionState()
        app.repository.refreshProtectionState()
    }
}
