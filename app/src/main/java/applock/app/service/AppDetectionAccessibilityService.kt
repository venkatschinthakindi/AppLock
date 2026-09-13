package applock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import applock.app.AppLockApplication
import applock.app.domain.SessionRule
import applock.app.engine.LockEngine
import applock.app.security.AntiTamperPolicy
import applock.app.ui.lock.LockActivity
import applock.app.ui.lock.SecurityGateActivity

/**
 * Foreground-package enforcement backend.
 *
 * Security rule: once a protected package is observed, fail closed. The
 * foreground app is sent home before the authentication activity is launched.
 * This prevents the protected app from winning the Activity-start race on the
 * first launch after configuration on devices/OEMs that deliver the
 * accessibility event after the target Activity has already started drawing.
 */
class AppDetectionAccessibilityService : AccessibilityService() {

    private val app: AppLockApplication
        get() = application as AppLockApplication

    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastExternalPackage: String? = null
    private var lockLaunchInProgress = false

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val accessibilityEvent = event ?: return

        when (accessibilityEvent.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> Unit
            else -> return
        }

        val pkg = accessibilityEvent.packageName
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: return

        if (pkg == packageName) {
            lastExternalPackage = null
            if (app.lockEngine.state.value != LockEngine.State.SHOWING_AUTH) {
                lockLaunchInProgress = false
            }
            return
        }

        val previous = lastExternalPackage
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

        // Always refresh the live protected-package snapshot before making a
        // security decision. This is important immediately after the user
        // selects an app in MainActivity.
        app.repository.refreshProtectionState()

        if (
            AntiTamperPolicy.isManagementPackage(pkg) &&
            app.repository.authenticationConfigured() &&
            app.repository.protectedPackages().isNotEmpty()
        ) {
            lastExternalPackage = pkg
            if (lockLaunchInProgress) return

            lockLaunchInProgress = true
            app.lockEngine.markAuthUiShown()
            launchSecurityGate()
            return
        }

        val shouldLock = app.lockEngine.onPackageVisible(pkg)
        lastExternalPackage = pkg

        if (!shouldLock) {
            lockLaunchInProgress = false
            return
        }

        if (lockLaunchInProgress) return

        lockLaunchInProgress = true
        app.lockEngine.markAuthUiShown()
        launchProtectedAppGate(pkg)
    }

    private fun launchProtectedAppGate(targetPackage: String) {
        /*
         * The AccessibilityService callback can arrive after the target app
         * has already drawn its first frame. Immediately returning HOME makes
         * the enforcement fail closed instead of relying on an Activity start
         * race. The short delayed launch gives Android time to complete the
         * global HOME transition before showing our gate.
         */
        runCatching {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }

        mainHandler.postDelayed({
            if (!lockLaunchInProgress) return@postDelayed

            try {
                startActivity(Intent(this, LockActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                    putExtra(LockActivity.EXTRA_PACKAGE_NAME, targetPackage)
                })
            } catch (_: ActivityNotFoundException) {
                lockLaunchInProgress = false
                app.lockEngine.resetTransitionState()
            } catch (_: SecurityException) {
                lockLaunchInProgress = false
                app.lockEngine.resetTransitionState()
            }
        }, AUTH_LAUNCH_DELAY_MS)
    }

    private fun launchSecurityGate() {
        mainHandler.postDelayed({
            if (!lockLaunchInProgress) return@postDelayed

            try {
                startActivity(Intent(this, SecurityGateActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                })
            } catch (_: ActivityNotFoundException) {
                lockLaunchInProgress = false
                app.lockEngine.resetTransitionState()
            } catch (_: SecurityException) {
                lockLaunchInProgress = false
                app.lockEngine.resetTransitionState()
            }
        }, MANAGEMENT_GATE_DELAY_MS)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED
            notificationTimeout = 0L
        }

        lastExternalPackage = null
        lockLaunchInProgress = false
        app.lockEngine.resetTransitionState()
        app.repository.refreshProtectionState()
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacksAndMessages(null)
        lastExternalPackage = null
        lockLaunchInProgress = false
        app.lockEngine.resetTransitionState()
        app.repository.refreshProtectionState()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        // Small enough to avoid visible lag, long enough for the HOME global
        // action to win against the target application's first Activity frame.
        const val AUTH_LAUNCH_DELAY_MS = 80L
        const val MANAGEMENT_GATE_DELAY_MS = 40L
    }
}
