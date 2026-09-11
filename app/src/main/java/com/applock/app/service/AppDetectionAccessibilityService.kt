package applock.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import applock.app.AppLockApplication
import applock.app.ui.lock.LockActivity

/** Narrow App Lock service: reads package name/window transitions only. */
class AppDetectionAccessibilityService : AccessibilityService() {
    private val app get() = application as AppLockApplication
    private var lastPackage: String? = null
    private var lastPromptAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg.isBlank()) return
        if (pkg == lastPackage && SystemClock.elapsedRealtime() - lastPromptAt < 300L) return
        lastPackage = pkg
        if (!app.repository.isProtected(pkg)) return
        if (!app.repository.shouldRequireAuth(pkg)) return
        lastPromptAt = SystemClock.elapsedRealtime()
        app.lockEngine.onPackageVisible(pkg)
        startActivity(Intent(this, LockActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            putExtra(LockActivity.EXTRA_PACKAGE_NAME, pkg)
        })
    }

    override fun onInterrupt() = Unit
}
