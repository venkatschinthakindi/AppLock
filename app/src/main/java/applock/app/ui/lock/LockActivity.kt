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

        packageNameTarget = intent
            .getStringExtra(EXTRA_PACKAGE_NAME)
            .orEmpty()

        val app = application as AppLockApplication

        /*
         * Fail closed if the authentication activity was started with
         * an invalid, empty, or no-longer-protected package.
         */
        if (
            packageNameTarget.isBlank() ||
            !app.repository.isProtected(packageNameTarget) ||
            packageManager.getLaunchIntentForPackage(packageNameTarget) == null
        ) {
            finishAndRemoveTask()
            return
        }

        app.lockEngine.markAuthUiShown()

        /*
         * Back must never reveal the protected application or bypass
         * the authentication screen.
         */
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Intentionally blocked.
                }
            }
        )

        setContent {
            LockScreen(
                packageName = packageNameTarget,
                onSuccess = {
                    returnToProtectedApp(packageNameTarget)
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val newTarget = intent
            .getStringExtra(EXTRA_PACKAGE_NAME)
            .orEmpty()

        val app = application as AppLockApplication

        /*
         * Do not silently switch the lock screen to an invalid target.
         */
        if (
            newTarget.isBlank() ||
            !app.repository.isProtected(newTarget) ||
            packageManager.getLaunchIntentForPackage(newTarget) == null
        ) {
            finishAndRemoveTask()
            return
        }

        packageNameTarget = newTarget
    }

    private fun returnToProtectedApp(targetPackage: String) {
        val app = application as AppLockApplication

        /*
        * Fail closed if protection was removed while the authentication
        * screen was displayed.
        */
        if (
            targetPackage.isBlank() ||
            !app.repository.isProtected(targetPackage)
        ) {
            app.lockEngine.reset()
            finishAndRemoveTask()
            return
        }

        val launchIntent = packageManager
            .getLaunchIntentForPackage(targetPackage)

        if (launchIntent == null) {
            app.lockEngine.reset()
            finishAndRemoveTask()
            return
        }

        /*
        * Record the successful authentication only for the exact
        * protected package that was authenticated.
        */
        app.lockEngine.unlock(targetPackage)

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)

        finishAndRemoveTask()
    }
}