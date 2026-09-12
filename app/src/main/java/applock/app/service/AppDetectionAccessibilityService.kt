package applock.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import applock.app.AppLockApplication
import applock.app.domain.SessionRule
import applock.app.engine.LockEngine
import applock.app.ui.lock.LockActivity

/**
 * Foreground-app detector for AppLock.
 *
 * Security decisions are delegated to LockEngine.
 *
 * This service:
 * - does not retrieve window content
 * - does not inspect text
 * - does not inspect passwords/messages
 * - does not poll
 * - does not perform network operations
 * - reacts only to package/window transition events
 */
class AppDetectionAccessibilityService : AccessibilityService() {

    private val app: AppLockApplication
        get() = application as AppLockApplication

    private var lastExternalPackage: String? = null
    private var lockLaunchInProgress = false

    override fun onServiceConnected() {
        super.onServiceConnected()

        /*
         * Re-apply the runtime event configuration. A zero timeout minimizes
         * batching latency; the engine still suppresses duplicate bursts.
         */
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 0L
        }

        lastExternalPackage = null
        lockLaunchInProgress = false
        app.lockEngine.resetTransitionState()
        app.repository.refreshProtectionState()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val accessibilityEvent = event ?: return

        when (accessibilityEvent.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> return
        }

        val pkg = accessibilityEvent.packageName
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return

        /*
         * Our own activities are never protected targets. They are also a
         * reliable transition boundary while LockActivity is on screen.
         */
        if (pkg == packageName) {
            /*
             * Keep lockLaunchInProgress while LockActivity is visible. Clearing
             * it here can allow a later duplicate protected-app event to create
             * a second lock screen over the first one.
             */
            lastExternalPackage = null
            if (app.lockEngine.state.value != LockEngine.State.SHOWING_AUTH) {
                lockLaunchInProgress = false
            }
            return
        }

        val previous = lastExternalPackage

        /*
         * Every real package transition creates a new decision boundary.
         * Pass the PREVIOUS package to the engine so the NEW package is never
         * accidentally marked as already processed.
         */
        if (previous != null && previous != pkg) {
            if (
                app.repository.getSessionRule() == SessionRule.AFTER_LEAVING &&
                app.repository.isProtected(previous)
            ) {
                app.repository.clearUnlock(previous)
            }

            app.lockEngine.onNonProtectedPackageVisible(previous)
            lockLaunchInProgress = false
        }

        /*
         * Ask the engine on every new package event. The engine owns the
         * short duplicate-event suppression and authentication decision.
         */
        val shouldLock = app.lockEngine.onPackageVisible(pkg)
        lastExternalPackage = pkg

        if (!shouldLock) {
            lockLaunchInProgress = false
            return
        }

        if (lockLaunchInProgress) {
            return
        }

        lockLaunchInProgress = true
        app.lockEngine.markAuthUiShown()

        try {
            startActivity(
                Intent(this, LockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(
                        LockActivity.EXTRA_PACKAGE_NAME,
                        pkg
                    )
                }
            )
        } catch (_: ActivityNotFoundException) {
            lockLaunchInProgress = false
            app.lockEngine.resetTransitionState()
        } catch (_: SecurityException) {
            lockLaunchInProgress = false
            app.lockEngine.resetTransitionState()
        }
    }

    override fun onInterrupt() {
        lastExternalPackage = null
        lockLaunchInProgress = false
        app.lockEngine.resetTransitionState()
        app.repository.refreshProtectionState()
    }
}
