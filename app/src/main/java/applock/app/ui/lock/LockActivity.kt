package applock.app.ui.lock

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewTreeObserver
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

        /*
        * V4:
        * Make the security Activity opaque from its first window frame.
        *
        * The service-side native accessibility barrier covers the interval
        * before this window is ready. This opaque background covers the
        * Activity's own startup/rendering interval.
        */
        overridePendingTransition(0, 0)
        window.setWindowAnimations(0)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

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

        /*
        * V4 privacy hand-off:
        *
        * onLockActivityShown() means the Activity exists.
        * It does NOT mean the Activity has been drawn.
        *
        * Wait for the first pre-draw, then tell the accessibility service that
        * it is safe to remove the native protection barrier.
        */
        val decor = window.decorView
        val observer = decor.viewTreeObserver

        observer.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {

                private var sent = false

                override fun onPreDraw(): Boolean {

                    if (!sent) {
                        sent = true

                        if (
                            packageNameTarget.isNotBlank() &&
                            requestId != 0L &&
                            !isFinishing &&
                            !isDestroyed
                        ) {
                            AppDetectionAccessibilityService.notifyLockActivityReady(
                                packageNameTarget,
                                requestId
                            )
                        }

                        if (observer.isAlive) {
                            observer.removeOnPreDrawListener(this)
                        }
                    }

                    return true
                }
            }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (authenticationCompleted) return

        if (bindRequest(intent)) {
            installLockUiReadyHandshake()
        }
    }

    /**
     * Validates the incoming request and adopts it. Returns false (after
     * finishing) when the request is not the live one.
     */
    private fun bindRequest(source: Intent?): Boolean {
        val app = application as AppLockApplication
        val target = source?.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        val id = source?.getLongExtra(EXTRA_REQUEST_ID, 0L) ?: 0L

        if (
            !isValidTarget(app, target) ||
            !app.lockEngine.isRequestActive(target, id)
        ) {
            finishAndRemoveTask()
            return false
        }

        packageNameTarget = target
        requestId = id
        app.lockEngine.markAuthUiShown()
        AppDetectionAccessibilityService.notifyLockActivityShown(target, id)
        return true
    }

    private fun installLockUiReadyHandshake() {
        val decor = window.decorView
        val observer = decor.viewTreeObserver

        observer.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {

                private var sent = false

                override fun onPreDraw(): Boolean {
                    if (!sent) {
                        sent = true

                        if (
                            packageNameTarget.isNotBlank() &&
                            requestId != 0L &&
                            !isFinishing &&
                            !isDestroyed
                        ) {
                            AppDetectionAccessibilityService.notifyLockActivityReady(
                                packageNameTarget,
                                requestId
                            )
                        }

                        if (observer.isAlive) {
                            observer.removeOnPreDrawListener(this)
                        }
                    }

                    return true
                }
            }
        )
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

        /*
         * Do NOT cancel the live authentication request from onStop().
         *
         * Android legitimately stops an Activity while the user is still
         * in the same authentication transition: launcher/Recents/SystemUI
         * can briefly become the reported foreground window, and biometric
         * or other system surfaces can temporarily cover the lock screen.
         *
         * Finishing here was the direct source of the observed flicker:
         * LockActivity -> launcher/SystemUI -> onStop() -> cancel request ->
         * service creates a new request -> LockActivity is launched again.
         *
         * The requestId remains the source of truth. Genuine hard boundaries
         * are handled by the service/LockEngine (AppLock main UI, real app
         * transition, and screen-off), while a stale Activity instance can
         * only cancel its own exact request in onDestroy().
         */
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

        // Belt-and-suspenders check: the PIN/pattern/biometric callback runs
        // synchronously on the main thread right after the engine call
        // succeeds, so there is no real gap for this activity to have been
        // torn down in between -- but if it ever were (a biometric prompt
        // completing after the system already stopped/finished this
        // activity for some other reason), never commit a launch from a
        // dead activity instance.
        if (isFinishing || isDestroyed) return

        if (!isValidTarget(app, targetPackage) ||
            !app.lockEngine.isAuthorizedForLaunch(targetPackage)
        ) {
            app.lockEngine.cancelRequest(targetPackage, requestId)
            finishAndRemoveTask()
            return
        }

        val launchIntent =
            packageManager.getLaunchIntentForPackage(targetPackage)

        if (launchIntent == null) {
            app.lockEngine.cancelRequest(targetPackage, requestId)
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
