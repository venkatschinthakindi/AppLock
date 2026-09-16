package applock.app.ui.lock

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication
import applock.app.service.AppDetectionAccessibilityService

/**
 * The protected-app challenge.
 *
 * Every instance is bound to exactly one authentication requestId. That token
 * is what makes the lock screen safe against the race that previously let apps
 * through: a stale instance resuming from the background can only ever cancel
 * *its own* request, never the fresh one created for a new launch.
 *
 * The activity also finishes as soon as it leaves the screen without a
 * successful authentication, so no stale challenge task can ever be resumed
 * in place of a new one.
 */
class LockActivity : FragmentActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "protected_package"
        const val EXTRA_REQUEST_ID = "protected_request_id"
    }

    private var packageNameTarget by mutableStateOf("")
    private var requestId = 0L
    private var authenticationCompleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        if (!bindRequest(intent)) return

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Never expose the protected application through Back.
                    // moveTaskToBack() would reveal the protected task behind
                    // this one, so leave explicitly through the launcher.
                    goHomeAndFinish()
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (authenticationCompleted) return
        bindRequest(intent)
    }

    /**
     * Validates the incoming request and adopts it. Returns false (after
     * finishing) when the request is not the live one.
     */
    private fun bindRequest(source: Intent?): Boolean {
        val app = application as AppLockApplication
        val target = source?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val id = source?.getLongExtra(EXTRA_REQUEST_ID, 0L) ?: 0L

        if (!isValidTarget(app, target) || !app.lockEngine.isRequestActive(target, id)) {
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            finishAndRemoveTask()
            return false
        }

        packageNameTarget = target
        requestId = id
        app.lockEngine.markAuthUiShown()
        AppDetectionAccessibilityService.notifyLockActivityShown(target, id)
        return true
    }

    override fun onResume() {
        super.onResume()
        if (packageNameTarget.isBlank()) return

        val app = application as AppLockApplication
        // Only ever act on our own request. If it is gone, this instance is
        // stale: disappear quietly without touching newer security state.
        if (!isValidTarget(app, packageNameTarget) ||
            (!app.lockEngine.isRequestActive(packageNameTarget, requestId) &&
                !app.lockEngine.isAuthorizedForLaunch(packageNameTarget))
        ) {
            finishAndRemoveTask()
            return
        }
        AppDetectionAccessibilityService.notifyLockActivityShown(packageNameTarget, requestId)
    }

    override fun onStop() {
        super.onStop()
        if (authenticationCompleted || isFinishing) return
        // The challenge left the screen without being satisfied (Home, recents,
        // power button, another app). Tear it down so the next entry always
        // produces a brand new challenge instead of resuming this task.
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        if (!authenticationCompleted) {
            val app = application as AppLockApplication
            if (packageNameTarget.isNotBlank() && requestId != 0L) {
                app.lockEngine.cancelRequest(packageNameTarget, requestId)
                AppDetectionAccessibilityService.notifyLockActivityDismissed(
                    packageNameTarget,
                    requestId
                )
            }
            AppDetectionAccessibilityService.releaseForegroundBarrier()
        }
        super.onDestroy()
    }

    private fun goHomeAndFinish() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        }
        finishAndRemoveTask()
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
            app.lockEngine.cancelRequest(targetPackage, requestId)
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            finishAndRemoveTask()
            return
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            app.lockEngine.cancelRequest(targetPackage, requestId)
            AppDetectionAccessibilityService.releaseForegroundBarrier()
            finishAndRemoveTask()
            return
        }

        authenticationCompleted = true
        AppDetectionAccessibilityService.notifyAuthenticationSucceeded(targetPackage, requestId)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launchIntent)
        finishAndRemoveTask()
    }
}
