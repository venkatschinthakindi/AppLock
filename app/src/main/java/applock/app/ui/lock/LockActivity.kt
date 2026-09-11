package applock.app.ui.lock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication

class LockActivity : FragmentActivity() {
    companion object { const val EXTRA_PACKAGE_NAME = "protected_package" }
    private lateinit var packageNameTarget: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        packageNameTarget = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        (application as AppLockApplication).lockEngine.markAuthUiShown()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Authentication cannot be bypassed with Back. Keep this activity in front.
            }
        })
        setContent { LockScreen(packageNameTarget, onSuccess = { finishAndRemoveTask() }) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        packageNameTarget = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
    }
}
