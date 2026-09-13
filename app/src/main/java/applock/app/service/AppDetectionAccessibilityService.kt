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
import applock.app.engine.LockEngine
import applock.app.security.AntiTamperManager
import applock.app.security.AntiTamperPolicy
import applock.app.ui.lock.LockActivity
import applock.app.ui.lock.SecurityGateActivity

/**
 * Foreground enforcement service.
 *
 * The critical path uses an accessibility overlay as a short-lived fail-closed
 * barrier. This avoids the Activity-start race where a protected application can
 * draw before LockActivity becomes the top activity.
 *
 * The overlay contains no credentials and does not inspect window content.
 */
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

        /*
         * Our UI is now on top. The lock/security Activity itself owns the
         * authentication interaction; never feed our own package back into
         * the protected-app decision path.
         */
        if (pkg == packageName) {
            lastExternalPackage = null
            return
        }

        /*
         * The user can leave the lock Activity with the system Home gesture.
         * In that case there is no authenticated-return token. Any subsequent
         * foreground package is a fresh decision boundary.
         */
        if (
            lockActivityShownTarget != null &&
            pkg != lockActivityShownTarget &&
            !app.repository.isProtected(pkg)
        ) {
            app.lockEngine.resetTransitionState()
            lockActivityShownTarget = null
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
            pendingTargetPackage = null
        }

        /*
         * A management authorization is a short-lived session, not a single
         * AccessibilityEvent. This is required because Settings may emit many
         * package/window events while the user navigates through App info,
         * Force stop, Storage/Clear cache and Uninstall surfaces.
         */
        if (
            managementAuthorizedUntilElapsed > SystemClock.elapsedRealtime() &&
            AntiTamperPolicy.isManagementPackage(pkg)
        ) {
            lastExternalPackage = pkg
            return
        }

        if (
            !AntiTamperPolicy.isManagementPackage(pkg)
        ) {
            managementAuthorizedUntilElapsed = 0L
        }

        app.repository.refreshProtectionState()

        if (
            lockActivityShownTarget == pkg &&
            !app.lockEngine.state.value.let { state ->
                state == LockEngine.State.UNLOCKED
            }
        ) {
            /*
             * Same protected package was returned to after leaving the lock UI
             * without successful authentication. Re-open the gate instead of
             * letting LockEngine's old transition marker suppress it.
             */
            app.lockEngine.resetTransitionState()
            lockActivityShownTarget = null
        }

        if (
            AntiTamperPolicy.isManagementPackage(pkg) &&
            app.repository.authenticationConfigured() &&
            app.repository.protectedPackages().isNotEmpty()
        ) {
            /*
             * SecurityGateActivity grants a package-scoped token after the
             * credential is verified. Consume it exactly once and convert it
             * into a short management navigation session.
             */
            if (AntiTamperManager.consumeManagementAccess(pkg)) {
                managementAuthorizedUntilElapsed =
                    SystemClock.elapsedRealtime() + MANAGEMENT_SESSION_TTL_MS
                removeProtectionOverlay()
                lockLaunchInProgress = false
                lastExternalPackage = pkg
                return
            }

            if (lockLaunchInProgress) {
                lastExternalPackage = pkg
                return
            }

            lockLaunchInProgress = true
            pendingTargetPackage = pkg
            showProtectionOverlay()
            app.lockEngine.markAuthUiShown()
            launchSecurityGate(pkg)
            lastExternalPackage = pkg
            return
        }

        /*
         * If we are retrying the same package after an Activity-start failure,
         * clear only the engine's transition marker before asking it to decide
         * again. This fixes the old "tap several times before it locks" state
         * without resetting authentication throttling.
         */
        if (
            pkg == pendingTargetPackage &&
            lockActivityShownTarget != pkg
        ) {
            app.lockEngine.resetTransitionState()
        }

        val shouldLock = app.lockEngine.onPackageVisible(pkg)
        lastExternalPackage = pkg

        if (!shouldLock) {
            /*
             * A protected app with an already-valid session can continue.
             * Do not leave a stale barrier from an earlier failed launch.
             */
            if (lockActivityShownTarget != pkg) {
                removeProtectionOverlay()
            }
            lockLaunchInProgress = false
            pendingTargetPackage = null
            return
        }

        if (lockLaunchInProgress) {
            return
        }

        lockLaunchInProgress = true
        pendingTargetPackage = pkg
        lockActivityShownTarget = null

        /*
         * Barrier first, Activity second.
         *
         * The protected app can no longer receive normal touch input while
         * Android is resolving/starting LockActivity.
         */
        showProtectionOverlay()
        app.lockEngine.markAuthUiShown()
        launchProtectedAppGate(pkg)
    }

    private fun launchProtectedAppGate(targetPackage: String) {
        mainHandler.post {
            if (!lockLaunchInProgress || pendingTargetPackage != targetPackage) {
                return@post
            }

            try {
                startActivity(
                    android.content.Intent(this, LockActivity::class.java).apply {
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        )
                        putExtra(LockActivity.EXTRA_PACKAGE_NAME, targetPackage)
                    }
                )

                /*
                 * If the Activity does not take ownership of the UI shortly
                 * after launch, retry once. The overlay remains in place, so
                 * the protected app is never exposed during this recovery.
                 */
                mainHandler.postDelayed({
                    if (
                        lockLaunchInProgress &&
                        pendingTargetPackage == targetPackage &&
                        lockActivityShownTarget != targetPackage
                    ) {
                        app.lockEngine.resetTransitionState()
                        launchProtectedAppGate(targetPackage)
                    }
                }, ACTIVITY_START_WATCHDOG_MS)
            } catch (_: Exception) {
                app.lockEngine.resetTransitionState()
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
            if (!lockLaunchInProgress) return@post

            try {
                startActivity(
                    android.content.Intent(this, SecurityGateActivity::class.java).apply {
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        )
                        putExtra(
                            SecurityGateActivity.EXTRA_MANAGEMENT_PACKAGE,
                            targetPackage
                        )
                    }
                )
            } catch (_: Exception) {
                removeProtectionOverlay()
                lockLaunchInProgress = false
                pendingTargetPackage = null
                lockActivityShownTarget = null
                app.lockEngine.resetTransitionState()
            }
        }
    }

    /**
     * Short-lived full-screen accessibility barrier.
     *
     * TYPE_ACCESSIBILITY_OVERLAY is provided specifically for an active
     * AccessibilityService and does not require SYSTEM_ALERT_WINDOW.
     */
    private fun showProtectionOverlay() {
        if (protectionOverlay != null) return

        val wm = windowManager ?: return

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
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
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        runCatching {
            wm.addView(overlay, params)
            protectionOverlay = overlay
        }
    }


    /** Called by LockActivity only after its window has been created successfully. */
    fun onLockActivityShown(targetPackage: String) {
        if (targetPackage.isBlank()) return
        if (pendingTargetPackage == targetPackage) {
            lockActivityShownTarget = targetPackage
            lockLaunchInProgress = false
            pendingTargetPackage = null
            removeProtectionOverlay()
        }
    }

    fun removeProtectionOverlay() {
        val overlay = protectionOverlay ?: return
        protectionOverlay = null

        runCatching {
            windowManager?.removeViewImmediate(overlay)
        }
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
        pendingTargetPackage = null
        lockActivityShownTarget = null
        lockLaunchInProgress = false
        managementAuthorizedUntilElapsed = 0L
        removeProtectionOverlay()
        app.lockEngine.resetTransitionState()
        app.repository.refreshProtectionState()
    }

    override fun onInterrupt() {
        mainHandler.removeCallbacksAndMessages(null)
        removeProtectionOverlay()
        lastExternalPackage = null
        pendingTargetPackage = null
        lockActivityShownTarget = null
        lockLaunchInProgress = false
        managementAuthorizedUntilElapsed = 0L
        app.lockEngine.resetTransitionState()
        app.repository.refreshProtectionState()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        removeProtectionOverlay()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    companion object {
        const val MANAGEMENT_SESSION_TTL_MS = 30_000L
        const val ACTIVITY_START_WATCHDOG_MS = 700L
        const val RETRY_DELAY_MS = 120L

        @Volatile
        private var instance: AppDetectionAccessibilityService? = null

        fun releaseForegroundBarrier() {
            instance?.removeProtectionOverlay()
        }

        fun notifyLockActivityShown(targetPackage: String) {
            instance?.onLockActivityShown(targetPackage)
        }
    }
}
