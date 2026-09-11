package applock.app.ui.lock

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication

class LockActivity : FragmentActivity() {
    companion object { const val EXTRA_PACKAGE_NAME = "protected_package" }

    private var packageNameTarget: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        packageNameTarget = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        (application as AppLockApplication).lockEngine.markAuthUiShown()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Never expose the protected app or the launcher behind the authentication UI.
            }
        })

        setContent {
            LockScreen(
                packageName = packageNameTarget,
                onSuccess = { returnToProtectedApp(packageNameTarget) }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        packageNameTarget = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
    }

    private fun returnToProtectedApp(targetPackage: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        }
        finishAndRemoveTask()
    }
}
