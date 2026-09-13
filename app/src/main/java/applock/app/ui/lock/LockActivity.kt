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

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        packageNameTarget =
            intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()

        val app = application as AppLockApplication

        if (!isValidTarget(app, packageNameTarget)) {
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            finishAndRemoveTask()
            return
        }

        app.lockEngine.markAuthUiShown()

        /*
         * The service keeps a touch-blocking accessibility overlay over the
         * protected app until this Activity has actually been created.
         *
         * This callback is intentionally made from the Activity rather than
         * from the AccessibilityService's startActivity() call. That removes
         * the old Activity-start race from the security state machine.
         */
        AppDetectionAccessibilityService.notifyLockActivityShown(
            packageNameTarget
        )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Never expose the protected application through Back.
                }
            }
        )

        setContent {
            LockScreen(
                packageName = packageNameTarget,
                onSuccess = {
                    completeAuthentication(packageNameTarget)
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val newTarget =
            intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()

        val app = application as AppLockApplication

        if (!isValidTarget(app, newTarget)) {
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            finishAndRemoveTask()
            return
        }

        if (!authenticationCompleted) {
            packageNameTarget = newTarget
            app.lockEngine.markAuthUiShown()
            AppDetectionAccessibilityService.notifyLockActivityShown(
                newTarget
            )
        }
    }

    private fun isValidTarget(
        app: AppLockApplication,
        targetPackage: String
    ): Boolean {
        if (targetPackage.isBlank()) return false

        if (!app.repository.isProtected(targetPackage)) {
            return false
        }

        return packageManager.getLaunchIntentForPackage(
            targetPackage
        ) != null
    }

    private fun completeAuthentication(targetPackage: String) {
        if (authenticationCompleted) return

        val app = application as AppLockApplication

        if (!isValidTarget(app, targetPackage)) {
            app.lockEngine.resetTransitionState()
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            finishAndRemoveTask()
            return
        }

        /*
         * Unlock is recorded before launching the target. The next foreground
         * event is therefore consumed by the engine's authenticated-return
         * token instead of immediately opening the lock again.
         */
        if (!app.lockEngine.unlock(targetPackage)) {
            return
        }

        val launchIntent =
            packageManager.getLaunchIntentForPackage(targetPackage)

        if (launchIntent == null) {
            app.lockEngine.resetTransitionState()
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            finishAndRemoveTask()
            return
        }

        authenticationCompleted = true

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        startActivity(launchIntent)

        /*
         * The overlay was already released when this Activity became visible.
         * The engine's one-time authenticated return token now protects the
         * transition back to the target application.
         */
        finishAndRemoveTask()
    }
}
