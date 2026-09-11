package applock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import applock.app.AppLockApplication
import applock.app.domain.SessionRule
import applock.app.ui.lock.LockActivity

/** Narrow App Lock service: reacts to package/window transitions only; it never reads window text/content. */
class AppDetectionAccessibilityService : AccessibilityService() {
    private val app get() = application as AppLockApplication
    private var lastPackage: String? = null
    private var lastPromptAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg.isBlank()) return

        val previous = lastPackage

        // Treat a package transition as the meaningful "app opened" event. This prevents
        // repeated window-state events from immediately reopening the lock over itself.
        if (pkg == previous) return

        if (previous != null && app.repository.getSessionRule() == SessionRule.AFTER_LEAVING) {
            app.repository.clearUnlock(previous)
        }

        lastPackage = pkg

        if (!app.repository.isProtected(pkg)) return
        if (!app.repository.shouldRequireAuth(pkg)) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastPromptAt < 500L) return
        lastPromptAt = now

        if (!app.lockEngine.onPackageVisible(pkg)) return

        startActivity(Intent(this, LockActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(LockActivity.EXTRA_PACKAGE_NAME, pkg)
        })
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastPackage = null
        lastPromptAt = 0L
        app.lockEngine.reset()
        app.repository.refreshProtectionState()
    }

    override fun onInterrupt() {
        lastPackage = null
        app.lockEngine.reset()
        app.repository.refreshProtectionState()
    }
}
