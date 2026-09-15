package applock.app.service

import android.accessibilityservice.AccessibilityService
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
import android.widget.FrameLayout
import applock.app.AppLockApplication
import applock.app.domain.SessionRule
import applock.app.security.AntiTamperManager
import applock.app.security.AntiTamperPolicy
import applock.app.ui.lock.LockActivity
import applock.app.ui.lock.SecurityGateActivity

/** Foreground protected-app enforcement. */
class AppDetectionAccessibilityService : AccessibilityService() {
    private val app: AppLockApplication
        get() = application as AppLockApplication

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastExternalPackage: String? = null
    private var pendingTargetPackage: String? = null
    private var lockLaunchInProgress = false
    private var lockActivityShownTarget: String? = null
    private var managementAuthorizedUntilElapsed = 0L
    private var protectionOverlay: View? = null
    private var windowManager: WindowManager? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return

        // Foreground enforcement must be driven by window transitions, not by
        // focus/content events. Those events occur constantly while a protected
        // app is being used and were the source of repeated authentication.
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return

        val pkg = e.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        val className = e.className

        // AppLock is an explicit boundary. Never interpret it as a protected
        // target, and invalidate any pending Chrome/WhatsApp/etc. request.
        if (pkg == packageName) {
            // LockActivity/SecurityGateActivity are also part of our package.
            // While either security UI is visible, the active protected-app
            // request MUST remain alive so the PIN/pattern/biometric callback
            // can finish it. A normal AppLock MainActivity event, however, is
            // an explicit boundary and must cancel any old protected request.
            val classText = className?.toString().orEmpty()
            val isSecurityUi = classText.endsWith(".LockActivity") ||
                classText.endsWith(".SecurityGateActivity")
            if (isSecurityUi) {
                // LockActivity/SecurityGateActivity belong to AppLock, but they
                // are security UI rather than the normal AppLock dashboard.
                // Never treat them as a request-cancellation boundary.
                removeProtectionOverlay()
                return
            }

            val previous = lastExternalPackage
            if (previous != null &&
                previous != packageName &&
                app.repository.getSessionRule() == SessionRule.AFTER_LEAVING &&
                app.repository.isProtected(previous)
            ) {
                app.repository.clearUnlock(previous)
            }
            app.lockEngine.onAppLockVisible()
            lastExternalPackage = null
            pendingTargetPackage = null
            lockLaunchInProgress = false
            lockActivityShownTarget = null
            managementAuthorizedUntilElapsed = 0L
            removeProtectionOverlay()
            return
        }

        val previous = lastExternalPackage
        val packageChanged = previous != null && previous != pkg
        if (packageChanged) {
            if (app.repository.getSessionRule() == SessionRule.AFTER_LEAVING &&
                app.repository.isProtected(previous!!)
            ) {
                app.repository.clearUnlock(previous)
            }
            app.lockEngine.onNonProtectedPackageVisible(previous!!)
            lockLaunchInProgress = false
            pendingTargetPackage = null
            lockActivityShownTarget = null
            removeProtectionOverlay()
        }

        // If a user leaves the lock UI using Home/back/system UI, invalidate the
        // pending request. A later protected-app entry must create a new request.
        if (lockActivityShownTarget != null && pkg != lockActivityShownTarget && !app.repository.isProtected(pkg)) {
            app.lockEngine.cancelAuthenticationForPackage(lockActivityShownTarget!!)
            lockActivityShownTarget = null
            lockLaunchInProgress = false
            pendingTargetPackage = null
            removeProtectionOverlay()
        }

        if (
            managementAuthorizedUntilElapsed > SystemClock.elapsedRealtime() &&
            AntiTamperPolicy.isManagementSurface(pkg, e.eventType, className)
        ) {
            lastExternalPackage = pkg
            return
        }

        if (!AntiTamperPolicy.isManagementSurface(pkg, e.eventType, className)) {
            managementAuthorizedUntilElapsed = 0L
        }

        app.repository.refreshProtectionState()
        AntiTamperManager.enforceStrongProtection(this, app.repository.protectedPackages())

        if (
            AntiTamperPolicy.isManagementSurface(pkg, e.eventType, className) &&
            app.repository.authenticationConfigured() &&
            app.repository.protectedPackages().isNotEmpty()
        ) {
            handleManagementSurface(pkg)
            lastExternalPackage = pkg
            return
        }

        // Do not reset the engine merely because the Activity watchdog fired.
        // The old implementation did exactly that, destroying the active auth
        // request and causing valid PINs to be rejected/repeated.
        val shouldLock = app.lockEngine.onPackageVisible(pkg)
        lastExternalPackage = pkg

        if (!shouldLock) {
            if (lockActivityShownTarget != pkg) removeProtectionOverlay()
            lockLaunchInProgress = false
            pendingTargetPackage = null
            return
        }

        // Engine has created the request. Keep exactly one launch in flight.
        if (lockLaunchInProgress) return

        lockLaunchInProgress = true
        pendingTargetPackage = pkg
        lockActivityShownTarget = null
        showProtectionOverlay()
        app.lockEngine.markAuthUiShown()
        launchProtectedAppGate(pkg)
    }

    private fun handleAppLockMainUiShown() {
        val previous = lastExternalPackage
        if (previous != null &&
            app.repository.getSessionRule() == SessionRule.AFTER_LEAVING &&
            app.repository.isProtected(previous)
        ) {
            app.repository.clearUnlock(previous)
        }

        app.lockEngine.onAppLockVisible()
        lastExternalPackage = null
        pendingTargetPackage = null
        lockLaunchInProgress = false
        lockActivityShownTarget = null
        managementAuthorizedUntilElapsed = 0L
        removeProtectionOverlay()
    }

    private fun handleManagementSurface(targetPackage: String) {
        if (AntiTamperManager.consumeManagementAccess(targetPackage)) {
            managementAuthorizedUntilElapsed = SystemClock.elapsedRealtime() + MANAGEMENT_SESSION_TTL_MS
            removeProtectionOverlay()
            lockLaunchInProgress = false
            pendingTargetPackage = null
            return
        }
        if (lockLaunchInProgress) return

        lockLaunchInProgress = true
        pendingTargetPackage = targetPackage
        showProtectionOverlay()
        app.lockEngine.markAuthUiShown()
        launchSecurityGate(targetPackage)
    }

    private fun launchProtectedAppGate(targetPackage: String) {
        mainHandler.post {
            if (!lockLaunchInProgress || pendingTargetPackage != targetPackage) return@post
            try {
                startActivity(
                    android.content.Intent(this, LockActivity::class.java).apply {
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
                        )
                        putExtra(LockActivity.EXTRA_PACKAGE_NAME, targetPackage)
                    }
                )
                // Retry only the Activity start. NEVER reset the engine request.
                mainHandler.postDelayed({
                    if (
                        lockLaunchInProgress &&
                        pendingTargetPackage == targetPackage &&
                        lockActivityShownTarget != targetPackage
                    ) {
                        launchProtectedAppGate(targetPackage)
                    }
                }, ACTIVITY_START_WATCHDOG_MS)
            } catch (_: Exception) {
                mainHandler.postDelayed({
                    if (
                        pendingTargetPackage == targetPackage &&
                        lockActivityShownTarget != targetPackage
                    ) {
                        launchProtectedAppGate(targetPackage)
                    }
                }, RETRY_DELAY_MS)
            }
        }
    }

    private fun launchSecurityGate(targetPackage: String) {
        mainHandler.post {
            if (!lockLaunchInProgress || pendingTargetPackage != targetPackage) return@post
            try {
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
            } catch (_: Exception) {
                removeProtectionOverlay()
                lockLaunchInProgress = false
                pendingTargetPackage = null
            }
        }
    }

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

    fun onLockActivityShown(targetPackage: String) {
        if (targetPackage.isBlank()) return
        if (pendingTargetPackage == targetPackage &&
            app.lockEngine.isAuthenticationRequestActive(targetPackage)
        ) {
            lockActivityShownTarget = targetPackage
            lockLaunchInProgress = false
            pendingTargetPackage = null
            removeProtectionOverlay()
        }
    }

    fun removeProtectionOverlay() {
        val overlay = protectionOverlay ?: return
        protectionOverlay = null
        runCatching { windowManager?.removeViewImmediate(overlay) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
            notificationTimeout = 100L
        }
        mainHandler.removeCallbacksAndMessages(null)
        lastExternalPackage = null
        pendingTargetPackage = null
        lockLaunchInProgress = false
        lockActivityShownTarget = null
        managementAuthorizedUntilElapsed = 0L
        removeProtectionOverlay()
        app.lockEngine.resetTransitionState()
        app.repository.refreshProtectionState()
        AntiTamperManager.enforceStrongProtection(this, app.repository.protectedPackages())
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacksAndMessages(null)
        removeProtectionOverlay()
        lastExternalPackage = null
        pendingTargetPackage = null
        lockLaunchInProgress = false
        lockActivityShownTarget = null
        managementAuthorizedUntilElapsed = 0L
        app.lockEngine.resetTransitionState()
        app.repository.refreshProtectionState()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        removeProtectionOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val MANAGEMENT_SESSION_TTL_MS = 30_000L
        const val ACTIVITY_START_WATCHDOG_MS = 700L
        const val RETRY_DELAY_MS = 120L

        @Volatile private var instance: AppDetectionAccessibilityService? = null

        fun releaseForegroundBarrier() {
            instance?.removeProtectionOverlay()
        }

        fun notifyLockActivityShown(targetPackage: String) {
            instance?.onLockActivityShown(targetPackage)
        }

        fun notifyAppLockMainUiShown() {
            instance?.handleAppLockMainUiShown()
        }
    }
}
