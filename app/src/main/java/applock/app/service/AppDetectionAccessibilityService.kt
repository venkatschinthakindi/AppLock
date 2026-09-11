package applock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import applock.app.AppLockApplication
import applock.app.domain.SessionRule
import applock.app.ui.lock.LockActivity

/**
 * Lightweight foreground-app detector.
 *
 * Security-sensitive decision making is delegated to LockEngine.
 *
 * This service:
 * - does not retrieve window content
 * - does not inspect text
 * - does not inspect passwords/messages
 * - does not poll
 * - does not perform network operations
 * - only reacts to package/window transition events
 */
class AppDetectionAccessibilityService : AccessibilityService() {

    private val app: AppLockApplication
        get() = application as AppLockApplication

    /*
     * Last external package observed by the service.
     *
     * IMPORTANT:
     * We intentionally do not use this as a permanent "same package means
     * ignore" gate. Android can omit/interleave accessibility events.
     *
     * The LockEngine performs the final decision.
     */
    private var lastExternalPackage: String? = null

    /*
     * Prevent duplicate LockActivity launches when Android emits multiple
     * qualifying events in a very short period.
     */
    private var lockLaunchInProgress = false

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
         * Never process our own UI as a protected application.
         *
         * However, do treat it as a transition boundary so that the next
         * protected-app event is not incorrectly suppressed by a stale
         * lastPackage value.
         */
        if (pkg == packageName) {
            lastExternalPackage = null
            return
        }

        /*
         * Any external package means the user is no longer exclusively inside
         * the previously observed protected application.
         */
        val previous = lastExternalPackage

        if (previous != null && previous != pkg) {
            /*
             * AFTER_LEAVING sessions are explicitly invalidated when the user
             * leaves the protected application.
             *
             * Only clear the previous package when it was actually protected.
             */
            if (
                app.repository.getSessionRule() == SessionRule.AFTER_LEAVING &&
                app.repository.isProtected(previous)
            ) {
                app.repository.clearUnlock(previous)
            }

            /*
             * This is also the signal that the user has left the previously
             * authenticated application.
             */
            app.lockEngine.onNonProtectedPackageVisible(pkg)
        }

        /*
         * Do not perform expensive package-manager or preference work before
         * the engine has a chance to reject the event.
         *
         * The engine itself performs the authoritative protection check.
         */
        val shouldLock = app.lockEngine.onPackageVisible(pkg)

        lastExternalPackage = pkg

        if (!shouldLock) {
            lockLaunchInProgress = false
            return
        }

        /*
         * Accessibility can generate several qualifying events around an app
         * transition. Do not launch multiple LockActivity instances.
         */
        if (lockLaunchInProgress) {
            return
        }

        lockLaunchInProgress = true
        app.lockEngine.markAuthUiShown()

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
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        lastExternalPackage = null
        lockLaunchInProgress = false

        app.lockEngine.reset()
        app.repository.refreshProtectionState()
    }

    override fun onInterrupt() {
        lastExternalPackage = null
        lockLaunchInProgress = false

        app.lockEngine.reset()
        app.repository.refreshProtectionState()
    }
}