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
    private var lockUiReadyReported = false

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

                override fun onPreDraw(): Boolean {
                    // Drawing is NOT proof that this Activity is actually
                    // the foreground/top window. On several OEM builds the
                    // protected app can receive a window-state event between
                    // our first draw and the Activity gaining focus. The
                    // service-side barrier must remain in place until the
                    // Activity reports real window focus.
                    if (observer.isAlive) {
                        observer.removeOnPreDrawListener(this)
                    }
                    return true
                }
            }
        )
    }

    /**
     * One-shot ready handshake for the exact authentication Activity/request.
     * Window focus is the security gate. A pre-draw callback is intentionally
     * not accepted as READY because drawing does not prove that this Activity
     * is the top/focused window.
     */
    private fun reportLockUiReady(source: String) {
        if (lockUiReadyReported) return
        if (packageNameTarget.isBlank() || requestId == 0L) return
        if (isFinishing || isDestroyed) return

        val app = application as AppLockApplication
        if (!app.lockEngine.isRequestActive(packageNameTarget, requestId)) return

        lockUiReadyReported = true

        android.util.Log.d(
            "AppLockDiag",
            "LockActivity report READY source=$source " +
                "pkg=$packageNameTarget requestId=$requestId"
        )

        AppDetectionAccessibilityService.notifyLockActivityReady(
            packageNameTarget,
            requestId
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (hasFocus) {
            // Window focus, unlike pre-draw, proves that this Activity is the
            // currently focused security UI. Keep the native barrier until
            // this point.
            window.decorView.postOnAnimation {
                reportLockUiReady("windowFocus")
            }
        } else {
            // Focus loss is NOT authentication and is also NOT proof that the
            // protected app is back in the foreground. It can happen for
            // biometric/system UI transitions. The accessibility service owns
            // the actual foreground decision and will reinstall the privacy
            // barrier only if the protected app is really exposed.
            //
            // Do not turn every focus loss into a white barrier: the barrier
            // is visual transition protection, not the authentication state.
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (authenticationCompleted) return

        if (bindRequest(intent)) {
            lockUiReadyReported = false
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
        // Intentionally no pre-draw -> READY transition here. A pre-drawn
        // Activity can still be behind the protected application on some
        // Android/OEM task transitions. Window focus is the minimum signal we
        // accept for releasing the fail-closed barrier.
    }

    override fun onResume() {
        super.onResume()
        if (packageNameTarget.isBlank()) return

        val app = application as AppLockApplication
        // Only ever act on our own request. If it is gone, this instance is
        // stale: disappear quietly without touching newer security state.
        if (
            !isValidTarget(app, packageNameTarget) ||
            !app.lockEngine.isRequestActive(packageNameTarget, requestId)
        ) {
            // A LockActivity exists only for a live authentication request.
            // An authorized package is not a reason for a stale lock Activity
            // to remain on screen after its request has completed.
            finishAndRemoveTask()
            return
        }
        AppDetectionAccessibilityService.notifyLockActivityShown(packageNameTarget, requestId)
    }

    override fun onStop() {
        super.onStop()

        // onStop() is not authentication and must never cancel the request.
        // It is also not proof that the protected app is visible: biometric
        // prompts and other system-owned surfaces can stop/focus-shift this
        // Activity temporarily. The service watchdog reconciles the actual
        // foreground package and decides whether a privacy barrier is needed.
    }

    override fun onDestroy() {
        if (!authenticationCompleted) {
            /*
             * Activity destruction is a lifecycle event, not proof that the
             * user left the protected app and not proof of authentication.
             * Never cancel the engine transaction here. Otherwise Android can
             * destroy this Activity during task/Recents/SystemUI transitions,
             * the request disappears, the barrier is removed, and the
             * protected app can become visible without a challenge.
             *
             * The service owns the transaction. This callback only tells it
             * that this exact Activity instance is no longer visible, so the
             * watchdog can keep the barrier up and relaunch the same request.
             */
            if (packageNameTarget.isNotBlank() && requestId != 0L) {
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
