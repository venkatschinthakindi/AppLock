package applock.app.ui.lock

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication
import applock.app.service.AppDetectionAccessibilityService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class LockActivity : FragmentActivity() {
    companion object {
        const val EXTRA_PACKAGE_NAME = "protected_package"
    }

    private var packageNameTarget by mutableStateOf("")
    private var authenticationCompleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        packageNameTarget = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val app = application as AppLockApplication

        if (!isValidTarget(app, packageNameTarget) ||
            !app.lockEngine.isAuthenticationRequestActive(packageNameTarget)
        ) {
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            app.lockEngine.cancelAuthenticationForPackage(packageNameTarget)
            finishAndRemoveTask()
            return
        }

        app.lockEngine.markAuthUiShown()
        AppDetectionAccessibilityService.notifyLockActivityShown(packageNameTarget)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Do not expose the protected application through Back.
                }
            }
        )

        setContent {
            LockScreen(
                packageName = packageNameTarget,
                onSuccess = { completeAuthentication(packageNameTarget) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (packageNameTarget.isBlank()) return

        val app = application as AppLockApplication
        if (!isValidTarget(app, packageNameTarget) ||
            (!app.lockEngine.isAuthenticationRequestActive(packageNameTarget) &&
                !app.lockEngine.isAuthorizedForLaunch(packageNameTarget))
        ) {
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            app.lockEngine.cancelAuthenticationForPackage(packageNameTarget)
            finishAndRemoveTask()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newTarget = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val app = application as AppLockApplication

        if (!isValidTarget(app, newTarget) ||
            !app.lockEngine.isAuthenticationRequestActive(newTarget)
        ) {
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            app.lockEngine.cancelAuthenticationForPackage(newTarget)
            finishAndRemoveTask()
            return
        }

        if (!authenticationCompleted) {
            packageNameTarget = newTarget
            app.lockEngine.markAuthUiShown()
            AppDetectionAccessibilityService.notifyLockActivityShown(newTarget)
        }
    }

    override fun onDestroy() {
        if (!authenticationCompleted) {
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            if (packageNameTarget.isNotBlank()) {
                val app = application as AppLockApplication
                app.lockEngine.cancelAuthenticationForPackage(packageNameTarget)
            }
        }
        super.onDestroy()
    }

    private fun isValidTarget(app: AppLockApplication, targetPackage: String): Boolean {
        if (targetPackage.isBlank()) return false
        if (targetPackage == packageName) return false
        if (!app.repository.isProtected(targetPackage)) return false
        return packageManager.getLaunchIntentForPackage(targetPackage) != null
    }

    private fun completeAuthentication(targetPackage: String) {
        if (authenticationCompleted) return
        val app = application as AppLockApplication

        if (!isValidTarget(app, targetPackage) ||
            !app.lockEngine.isAuthorizedForLaunch(targetPackage)
        ) {
            app.lockEngine.cancelAuthenticationForPackage(targetPackage)
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            finishAndRemoveTask()
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            app.lockEngine.cancelAuthenticationForPackage(targetPackage)
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            finishAndRemoveTask()
            return
        }

        authenticationCompleted = true
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        finishAndRemoveTask()
    }
}
