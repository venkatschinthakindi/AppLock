package applock.app.ui.lock

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class LockActivity : FragmentActivity() {
    companion object {
        const val EXTRA_PACKAGE_NAME = "protected_package"
    }

    private var packageNameTarget by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        packageNameTarget = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val app = application as AppLockApplication

        if (!isValidTarget(packageNameTarget)) {
            finishAndRemoveTask()
            return
        }

        app.lockEngine.markAuthUiShown()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Back cannot dismiss the security surface and reveal the target app.
                }
            }
        )

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
        val newTarget = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        if (!isValidTarget(newTarget)) {
            finishAndRemoveTask()
            return
        }
        packageNameTarget = newTarget
        (application as AppLockApplication).lockEngine.markAuthUiShown()
    }

    private fun isValidTarget(targetPackage: String): Boolean {
        if (targetPackage.isBlank()) return false
        val app = application as AppLockApplication
        return app.repository.isProtected(targetPackage) &&
            packageManager.getLaunchIntentForPackage(targetPackage) != null
    }

    private fun returnToProtectedApp(targetPackage: String) {
        val app = application as AppLockApplication

        if (!isValidTarget(targetPackage)) {
            app.lockEngine.reset()
            finishAndRemoveTask()
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            app.lockEngine.reset()
            finishAndRemoveTask()
            return
        }

        // LockScreen already obtained the authoritative engine authorization.
        // Do not call a second generic unlock method here.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        finishAndRemoveTask()
    }
}
