package applock.app.security

import android.view.Window
import android.view.WindowManager

object LockWindowPolicy {
    fun secure(window: Window) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
}
