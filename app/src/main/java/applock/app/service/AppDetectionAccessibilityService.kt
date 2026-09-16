package applock.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import applock.app.AppLockApplication
import applock.app.domain.SessionRule
import applock.app.engine.LockEngine
import applock.app.security.AntiTamperManager
import applock.app.security.AntiTamperPolicy
import applock.app.ui.lock.LockActivity
import applock.app.ui.lock.SecurityGateActivity

/**
 * Foreground protected-app enforcement.
 *
 * Two independent sources of truth are used, because relying on accessibility
 * events alone is what allowed challenges to be skipped:
 *
 *  1. Accessibility window events (fast path).
 *  2. A periodic foreground watchdog that reads the actual top application
 *     window (authoritative path). It runs whenever a challenge is pending or
 *     a session is live, so a dropped, coalesced or OEM-suppressed event can
 *     never leave a protected app exposed, and a lock screen that loses focus
 *     is immediately re-asserted.
 */
class AppDetectionAccessibilityService : AccessibilityService() {

    private val app: AppLockApplication
        get() = application as AppLockApplication

    private val mainHandler = Handler(Looper.getMainLooper())

    private data class PendingLock(
        val packageName: String,
        val requestId: Long,
        var lockUiVisible: Boolean = false,
        var lastLaunchElapsed: Long = 0L
    )

    @Volatile private var pending: PendingLock? = null
    private var lastForegroundPackage: String? = null
    private var managementAuthorizedUntilElapsed = 0L
    private var protectionOverlay: View? = null
    private var windowManager: WindowManager? = null
    private var watchdogArmed = false
    private var lastProtectionRefreshElapsed = 0L
    private var gateLaunchElapsed = 0L

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED
            notificationTimeout = 0L
            // Required so getWindows() can report the real top application
            // window. Only window package names are read; no content is used.
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        ForegroundPolicy.refresh(this, force = true)
        hardReset()
        app.repository.refreshProtectionState()
        AntiTamperManager.enforceStrongProtection(this, app.repository.protectedPackages())
    }

    // ------------------------------------------------------------- event path

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val pkg = e.packageName?.toString()?.takeIf { it.isNotBlank() }
            ?: run {
                // A window changed but we do not know whose. Do not guess:
                // let the watchdog resolve the real top package.
                armWatchdog()
                return
            }

        handleForeground(pkg, e.className, e.eventType)
    }

    private fun handleForeground(
        pkg: String,
        className: CharSequence?,
        eventType: Int
    ) {
        when (ForegroundPolicy.classify(this, pkg, className, eventType, packageName)) {

            ForegroundPolicy.Surface.OUR_SECURITY_UI -> {
                // Our own lock/gate UI. It must never cancel a request or a
                // session, and the transition overlay is no longer needed.
                app.lockEngine.onSecurityUiVisible()
                removeProtectionOverlay()
                armWatchdog()
                return
            }

            ForegroundPolicy.Surface.OUR_MAIN_UI -> {
                handleAppLockMainUiShown()
                return
            }

            ForegroundPolicy.Surface.NON_DEPARTURE -> {
                // IME / permission dialog / share sheet: the user is still
                // inside the app. Park the session, keep any pending challenge.
                app.lockEngine.onNonDepartureSurface()
                armWatchdog()
                return
            }

            ForegroundPolicy.Surface.DEPARTURE -> {
                onUserLeft(pkg)
                return
            }

            ForegroundPolicy.Surface.APP -> Unit
        }

        // --- a real application package is in the foreground ------------------

        if (
            managementAuthorizedUntilElapsed > SystemClock.elapsedRealtime() &&
            AntiTamperPolicy.isManagementSurface(pkg, eventType, className)
        ) {
            lastForegroundPackage = pkg
            return
        }
        if (!AntiTamperPolicy.isManagementSurface(pkg, eventType, className)) {
            managementAuthorizedUntilElapsed = 0L
        }

        // The watchdog runs several times a second; refreshing live Android
        // protection state on every tick would be wasteful. Protection changes
        // are also pushed from the UI, so a short throttle is safe.
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastProtectionRefreshElapsed >= PROTECTION_REFRESH_INTERVAL_MS) {
            lastProtectionRefreshElapsed = nowElapsed
            app.repository.refreshProtectionState()
            AntiTamperManager.enforceStrongProtection(this, app.repository.protectedPackages())
        }

        if (
            AntiTamperPolicy.isManagementSurface(pkg, eventType, className) &&
            app.repository.authenticationConfigured() &&
            app.repository.protectedPackages().isNotEmpty()
        ) {
            handleManagementSurface(pkg)
            lastForegroundPackage = pkg
            return
        }

        val previous = lastForegroundPackage
        if (previous != null && previous != pkg && app.repository.isProtected(previous)) {
            // Session rules that end when the user leaves the app.
            val rule = app.repository.getSessionRule()
            if (rule == SessionRule.AFTER_LEAVING || rule == SessionRule.IMMEDIATELY) {
                app.repository.clearUnlock(previous)
            }
        }
        lastForegroundPackage = pkg

        val decision = app.lockEngine.onForegroundApp(pkg)
        applyDecision(decision)
    }

    /** The user is on the launcher / recents / another entry point. */
    private fun onUserLeft(pkg: String) {
        val previous = lastForegroundPackage
        if (previous != null && app.repository.isProtected(previous)) {
            val rule = app.repository.getSessionRule()
            if (rule == SessionRule.AFTER_LEAVING || rule == SessionRule.IMMEDIATELY) {
                app.repository.clearUnlock(previous)
            }
        }
        app.lockEngine.onUserLeftForeground()
        lastForegroundPackage = if (ForegroundPolicy.isLauncher(pkg)) pkg else null
        clearPending()
        removeProtectionOverlay()
        disarmWatchdogIfIdle()
    }

    private fun applyDecision(decision: LockEngine.Decision) {
        if (!decision.requireAuth) {
            // No challenge needed for the current foreground app.
            if (pending != null) clearPending()
            removeProtectionOverlay()
            if (app.lockEngine.hasActiveSession()) {
                // A live session must be watched: if the "user left" event is
                // ever dropped, the watchdog is what ends the session and makes
                // the next entry challenge again.
                armWatchdog()
            } else {
                disarmWatchdogIfIdle()
            }
            return
        }

        val current = pending
        if (current != null &&
            current.packageName == decision.packageName &&
            current.requestId == decision.requestId
        ) {
            // Same challenge, still in flight. Never disarm it here: that was
            // the race that let protected apps through while the lock screen
            // was still starting.
            if (!current.lockUiVisible) {
                showProtectionOverlay()
                maybeRelaunchLockUi(current)
            }
            armWatchdog()
            return
        }

        pending = PendingLock(decision.packageName, decision.requestId)
        showProtectionOverlay()
        app.lockEngine.markAuthUiShown()
        launchProtectedAppGate(decision.packageName, decision.requestId)
        armWatchdog()
    }

    // --------------------------------------------------------- watchdog path

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!watchdogArmed) return
            runCatching { watchdogTick() }
            mainHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private fun armWatchdog() {
        if (watchdogArmed) return
        watchdogArmed = true
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    private fun disarmWatchdogIfIdle() {
        if (pending == null && !app.lockEngine.hasActiveSession()) {
            watchdogArmed = false
            mainHandler.removeCallbacks(watchdogRunnable)
        }
    }

    private fun watchdogTick() {
        val top = resolveTopApplicationPackage()
        val p = pending

        if (top != null && top != packageName) {
            // Ground truth disagreed with (or never arrived as) an event.
            if (top != lastForegroundPackage || (p != null && top == p.packageName)) {
                handleForeground(top, null, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
            }
        }

        val current = pending ?: run { disarmWatchdogIfIdle(); return }

        val topIsOurs = top == null || top == packageName
        if (!topIsOurs && top == current.packageName) {
            // The protected app is on screen and our lock UI is not. Re-assert
            // the barrier immediately and retry the lock screen.
            current.lockUiVisible = false
            showProtectionOverlay()
            maybeRelaunchLockUi(current)
        } else if (!current.lockUiVisible) {
            showProtectionOverlay()
            maybeRelaunchLockUi(current)
        }
    }

    /**
     * Reads the package that actually owns the focused application window.
     * Returns null when it cannot be determined; callers must then fall back
     * to event state rather than assuming anything.
     */
    private fun resolveTopApplicationPackage(): String? = runCatching {
        val list: List<AccessibilityWindowInfo> = windows ?: emptyList()
        var candidate: String? = null
        for (w in list) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (!w.isActive && !w.isFocused) continue
            val root = w.root ?: continue
            val name = root.packageName?.toString()
            if (!name.isNullOrBlank()) {
                candidate = name
                break
            }
        }
        candidate
    }.getOrNull()

    // ------------------------------------------------------------ lock UI ops

    private fun maybeRelaunchLockUi(current: PendingLock) {
        val now = SystemClock.elapsedRealtime()
        if (now - current.lastLaunchElapsed < ACTIVITY_START_WATCHDOG_MS) return
        if (!app.lockEngine.isRequestActive(current.packageName, current.requestId)) {
            clearPending()
            removeProtectionOverlay()
            return
        }
        launchProtectedAppGate(current.packageName, current.requestId)
    }

    private fun launchProtectedAppGate(targetPackage: String, requestId: Long) {
        val current = pending ?: return
        if (current.packageName != targetPackage || current.requestId != requestId) return
        current.lastLaunchElapsed = SystemClock.elapsedRealtime()

        mainHandler.post {
            val live = pending ?: return@post
            if (live.packageName != targetPackage || live.requestId != requestId) return@post
            if (!app.lockEngine.isRequestActive(targetPackage, requestId)) return@post
            runCatching {
                startActivity(
                    android.content.Intent(this, LockActivity::class.java).apply {
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
                        )
                        putExtra(LockActivity.EXTRA_PACKAGE_NAME, targetPackage)
                        putExtra(LockActivity.EXTRA_REQUEST_ID, requestId)
                    }
                )
            }
            armWatchdog()
        }
    }

    private fun handleManagementSurface(targetPackage: String) {
        if (AntiTamperManager.consumeManagementAccess(targetPackage)) {
            managementAuthorizedUntilElapsed = SystemClock.elapsedRealtime() + MANAGEMENT_SESSION_TTL_MS
            removeProtectionOverlay()
            clearPending()
            gateLaunchElapsed = 0L
            return
        }

        // Reaching a management surface means the user left whatever protected
        // app they were in. That session must not survive, or returning to the
        // app afterwards would skip the challenge.
        app.lockEngine.onUserLeftForeground()
        clearPending()

        if (pending != null) return
        val now = SystemClock.elapsedRealtime()
        if (now - gateLaunchElapsed < GATE_RELAUNCH_INTERVAL_MS) return
        gateLaunchElapsed = now

        showProtectionOverlay()
        app.lockEngine.markAuthUiShown()
        mainHandler.post {
            runCatching {
                startActivity(
                    android.content.Intent(this, SecurityGateActivity::class.java).apply {
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
                        )
                        putExtra(SecurityGateActivity.EXTRA_MANAGEMENT_PACKAGE, targetPackage)
                    }
                )
            }.onFailure { removeProtectionOverlay() }
        }
    }

    private fun handleAppLockMainUiShown() {
        val previous = lastForegroundPackage
        if (previous != null && app.repository.isProtected(previous)) {
            val rule = app.repository.getSessionRule()
            if (rule == SessionRule.AFTER_LEAVING || rule == SessionRule.IMMEDIATELY) {
                app.repository.clearUnlock(previous)
            }
        }
        app.lockEngine.onAppLockVisible()
        lastForegroundPackage = null
        clearPending()
        managementAuthorizedUntilElapsed = 0L
        removeProtectionOverlay()
        disarmWatchdogIfIdle()
    }

    private fun clearPending() {
        pending = null
    }

    // ------------------------------------------------------------- overlay ops

    private fun showProtectionOverlay() {
        if (protectionOverlay != null) return
        val wm = windowManager ?: return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
            setOnTouchListener { _: View, _: MotionEvent -> true }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching {
            wm.addView(overlay, params)
            protectionOverlay = overlay
        }
    }

    fun removeProtectionOverlay() {
        val overlay = protectionOverlay ?: return
        protectionOverlay = null
        runCatching { windowManager?.removeViewImmediate(overlay) }
    }

    // ------------------------------------------------------------- callbacks

    fun onLockActivityShown(targetPackage: String, requestId: Long) {
        val current = pending ?: return
        if (current.packageName != targetPackage || current.requestId != requestId) return
        current.lockUiVisible = true
        removeProtectionOverlay()
    }

    fun onLockActivityDismissed(targetPackage: String, requestId: Long) {
        val current = pending ?: return
        if (current.packageName != targetPackage || current.requestId != requestId) return
        clearPending()
        removeProtectionOverlay()
        disarmWatchdogIfIdle()
    }

    fun onAuthenticationSucceeded(targetPackage: String, requestId: Long) {
        val current = pending
        if (current != null &&
            current.packageName == targetPackage &&
            current.requestId == requestId
        ) {
            clearPending()
        }
        lastForegroundPackage = targetPackage
        removeProtectionOverlay()
        armWatchdog()
    }

    private fun hardReset() {
        mainHandler.removeCallbacksAndMessages(null)
        watchdogArmed = false
        lastForegroundPackage = null
        clearPending()
        managementAuthorizedUntilElapsed = 0L
        lastProtectionRefreshElapsed = 0L
        gateLaunchElapsed = 0L
        removeProtectionOverlay()
        app.lockEngine.resetTransitionState()
    }

    override fun onInterrupt() {
        hardReset()
        app.repository.refreshProtectionState()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        watchdogArmed = false
        removeProtectionOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val MANAGEMENT_SESSION_TTL_MS = 30_000L
        const val ACTIVITY_START_WATCHDOG_MS = 600L
        const val WATCHDOG_INTERVAL_MS = 250L
        const val PROTECTION_REFRESH_INTERVAL_MS = 1_000L
        const val GATE_RELAUNCH_INTERVAL_MS = 1_500L

        @Volatile private var instance: AppDetectionAccessibilityService? = null

        fun releaseForegroundBarrier() {
            instance?.removeProtectionOverlay()
        }

        fun notifyLockActivityShown(targetPackage: String, requestId: Long) {
            instance?.onLockActivityShown(targetPackage, requestId)
        }

        fun notifyLockActivityDismissed(targetPackage: String, requestId: Long) {
            instance?.onLockActivityDismissed(targetPackage, requestId)
        }

        fun notifyAuthenticationSucceeded(targetPackage: String, requestId: Long) {
            instance?.onAuthenticationSucceeded(targetPackage, requestId)
        }

        fun notifyAppLockMainUiShown() {
            instance?.handleAppLockMainUiShown()
        }

        /** Called when the screen turns off / the device is locked. */
        fun notifyScreenOff() {
            instance?.let { service ->
                service.lastForegroundPackage = null
                service.clearPending()
                service.removeProtectionOverlay()
                service.disarmWatchdogIfIdle()
            }
        }
    }
}
