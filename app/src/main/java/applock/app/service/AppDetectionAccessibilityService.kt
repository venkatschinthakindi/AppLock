package applock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import applock.app.AppLockApplication
import applock.app.domain.SessionRule
import applock.app.security.AntiTamperManager
import applock.app.security.AntiTamperPolicy
import applock.app.ui.lock.LockActivity
import applock.app.ui.lock.SecurityGateActivity
import applock.app.engine.LockEngine

class AppDetectionAccessibilityService : AccessibilityService() {
    private val app: AppLockApplication
        get() = application as AppLockApplication

    private var lastExternalPackage: String? = null
    private var lockLaunchInProgress = false
    private var lastProtectionSignature: Int? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val accessibilityEvent = event ?: return
        when (accessibilityEvent.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> Unit
            else -> return
        }

        // Always refresh the persisted protection set before making a security decision.
        // This closes the configuration -> first-launch race: if the user just added
        // the foreground app to the protected list, this event sees the new policy.
        app.repository.refreshProtectionState()
        val protectionSignature = app.repository.protectedPackages().sorted().hashCode()
        if (lastProtectionSignature != protectionSignature) {
            lastProtectionSignature = protectionSignature
            lastExternalPackage = null
            lockLaunchInProgress = false
            app.lockEngine.resetTransitionState()
        }

        val pkg = accessibilityEvent.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return

        if (pkg == packageName) {
            lastExternalPackage = null
            if (app.lockEngine.state.value != LockEngine.State.SHOWING_AUTH) {
                lockLaunchInProgress = false
            }
            return
        }

        val previous = lastExternalPackage
        if (previous != null && previous != pkg) {
            if (app.repository.getSessionRule() == SessionRule.AFTER_LEAVING && app.repository.isProtected(previous)) {
                app.repository.clearUnlock(previous)
            }
            app.lockEngine.onNonProtectedPackageVisible(previous)
            lockLaunchInProgress = false
            if (!AntiTamperPolicy.isManagementPackage(pkg)) {
                AntiTamperManager.clearManagementAccess()
            }
        }

        if (
            AntiTamperPolicy.isManagementPackage(pkg) &&
            app.repository.authenticationConfigured()
        ) {
            if (AntiTamperManager.consumeManagementAccess(pkg)) {
                lastExternalPackage = pkg
                lockLaunchInProgress = false
                return
            }
            // Once the user has authenticated into a management surface, keep
            // that surface usable while it remains the foreground package. A
            // transition to another management package (for example Settings
            // -> Package Installer) creates a new authentication boundary.
            if (previous == pkg) {
                lastExternalPackage = pkg
                lockLaunchInProgress = false
                return
            }
            lastExternalPackage = pkg
            if (lockLaunchInProgress) return
            lockLaunchInProgress = true
            app.lockEngine.markAuthUiShown()
            try {
                startActivity(Intent(this, SecurityGateActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    putExtra(SecurityGateActivity.EXTRA_MANAGEMENT_PACKAGE, pkg)
                })
            } catch (_: ActivityNotFoundException) {
                lockLaunchInProgress = false
                app.lockEngine.resetTransitionState()
            } catch (_: SecurityException) {
                lockLaunchInProgress = false
                app.lockEngine.resetTransitionState()
            }
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
        try {
            startActivity(Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                putExtra(LockActivity.EXTRA_PACKAGE_NAME, pkg)
            })
        } catch (_: ActivityNotFoundException) {
            lockLaunchInProgress = false
            app.lockEngine.resetTransitionState()
        } catch (_: SecurityException) {
            lockLaunchInProgress = false
            app.lockEngine.resetTransitionState()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
            notificationTimeout = 0L
        }
        lastExternalPackage = null
        lockLaunchInProgress = false
        app.repository.refreshProtectionState()
        lastProtectionSignature = app.repository.protectedPackages().sorted().hashCode()
        app.lockEngine.resetTransitionState()
    }

    override fun onInterrupt() {
        lastExternalPackage = null
        lockLaunchInProgress = false
        app.lockEngine.resetTransitionState()
        app.repository.refreshProtectionState()
        AntiTamperManager.clearManagementAccess()
    }
}
