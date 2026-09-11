package applock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import applock.app.AppLockApplication
import applock.app.domain.SessionRule
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

    /**
     * Last external package observed.
     *
     * This is transition state only. It is NOT used to permanently suppress
     * the same package because Android/OEM accessibility event ordering is
     * not guaranteed.
     */
    private var lastExternalPackage: String? = null

    /**
     * Prevent multiple LockActivity launches from a burst of accessibility
     * events while the first lock screen is already being opened.
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
         * Our own LockActivity/MainActivity must never be treated as a
         * protected external application.
         *
         * Seeing our own package is also a useful transition boundary.
         */
        if (pkg == packageName) {
            lastExternalPackage = null
            lockLaunchInProgress = false
            return
        }

        val previous = lastExternalPackage

        /*
         * If the foreground package changed, explicitly tell the engine
         * that the PREVIOUS package is no longer the foreground package.
         *
         * IMPORTANT:
         *
         * The old implementation called:
         *
         *     onNonProtectedPackageVisible(pkg)
         *
         * before onPackageVisible(pkg).
         *
         * That was wrong because the engine then recorded the NEW package
         * as already processed, causing onPackageVisible(pkg) to reject it.
         *
         * We now notify the engine about the PREVIOUS package instead.
         */
        if (previous != null && previous != pkg) {

            /*
             * AFTER_LEAVING invalidates the previous protected application's
             * unlock session as soon as another package becomes visible.
             */
            if (
                app.repository.getSessionRule() == SessionRule.AFTER_LEAVING &&
                app.repository.isProtected(previous)
            ) {
                app.repository.clearUnlock(previous)
            }

            /*
             * Establish the previous package as the transition boundary.
             * This MUST happen before processing the new package.
             */
            app.lockEngine.onNonProtectedPackageVisible(previous)

            /*
             * The new package is a fresh foreground candidate.
             */
            lockLaunchInProgress = false
        }

        /*
         * Ask the engine to make the authoritative protection decision.
         */
        val shouldLock = app.lockEngine.onPackageVisible(pkg)

        /*
         * Update transition state only after the engine has evaluated the
         * current package.
         */
        lastExternalPackage = pkg

        if (!shouldLock) {
            /*
             * The engine rejected this event as either:
             * - unprotected
             * - already authenticated
             * - duplicate accessibility event
             * - timed session still valid
             * - authentication temporarily blocked
             *
             * A later genuine transition must be allowed to launch normally.
             */
            lockLaunchInProgress = false
            return
        }

        /*
         * Do not launch multiple lock activities for the same accessibility
         * transition burst.
         */
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
            /*
             * Fail closed if the lock activity cannot be resolved.
             *
             * Do not leave the service permanently stuck in a
             * "launch in progress" state.
             */
            lockLaunchInProgress = false
            app.lockEngine.reset()
        } catch (_: SecurityException) {
            /*
             * OEM/system-level activity launch failure.
             *
             * Clear transient launch state so a later transition can
             * attempt protection again.
             */
            lockLaunchInProgress = false
            app.lockEngine.reset()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        lastExternalPackage = null
        lockLaunchInProgress = false

        /*
         * A service recreation means its transition state is no longer
         * trustworthy. Reset only the process-local transition state.
         */
        app.lockEngine.resetTransitionState()

        /*
         * Refresh diagnostics/UI state. The engine does not depend on the
         * cached accessibility flag for an event that is already being
         * delivered by this service.
         */
        app.repository.refreshProtectionState()
    }

    override fun onInterrupt() {
        lastExternalPackage = null
        lockLaunchInProgress = false

        app.lockEngine.resetTransitionState()
        app.repository.refreshProtectionState()
    }
}