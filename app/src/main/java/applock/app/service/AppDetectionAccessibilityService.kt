package applock.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
 * Detection combines:
 *
 * 1. Accessibility window/activity events.
 * 2. Accessibility getWindows() watchdog.
 * 3. UsageStatsForegroundSource.
 *
 * Management/uninstall authentication is checked independently from
 * ordinary protected-app classification.
 *
 * V8:
 *
 * - Keeps the V7 native opaque privacy barrier.
 * - Keeps barrier ownership/deduplication.
 * - Keeps LockEngine as the sole owner of authentication transactions.
 * - Keeps requestId/package validation.
 * - Keeps the V7 departure debounce and watchdog timings.
 * - Reduces unnecessary work on the critical protected-app event path.
 * - Avoids expensive accessibility-tree management inspection unless the
 *   event is actually a management candidate.
 * - Keeps LockActivity launch request validation immediately before launch.
 */
class AppDetectionAccessibilityService : AccessibilityService() {

    /**
     * Security lifecycle of the accessibility service.
     *
     * A non-READY state is never interpreted as "nothing is protected".
     * Android may pause/recreate the process while the screen is off, so a
     * reconnect must rebuild runtime authentication state from durable
     * protection configuration and the current foreground package.
     */
    private enum class ServiceRuntimeState {
        STARTING,
        RECOVERING,
        READY,
        DISCONNECTED
    }

    @Volatile
    private var runtimeState = ServiceRuntimeState.STARTING

    private var recoveryGeneration = 0L

    private val app: AppLockApplication
        get() = application as AppLockApplication

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private data class PendingLock(
        val packageName: String,
        val requestId: Long,
        var lockUiVisible: Boolean = false,
        var lockUiReady: Boolean = false,
        var sponsorContinued: Boolean = false,
        var launchInFlight: Boolean = false,
        var lastLaunchElapsed: Long = 0L
    )

    @Volatile
    private var pending: PendingLock? = null

    private var lastForegroundPackage: String? = null

    private var managementAuthorizedUntilElapsed = 0L

    /** True while a no-protected-app configuration redirect is being launched. */
    private var configurationRedirectInProgress = false

    private var protectionOverlay: View? = null

    private var windowManager: WindowManager? = null

    private var watchdogArmed = false

    private var lastProtectionRefreshElapsed = 0L

    /**
     * In-memory protected-package snapshot used by the earliest
     * privacy-barrier path.
     *
     * The accessibility event path must not perform repository work
     * before the barrier is installed.
     */
    @Volatile
    private var protectedPackageSnapshot: Set<String> = emptySet()

    /**
     * The snapshot is deliberately separate from its contents. An empty set
     * is a valid configuration, but it must never mean "we have not loaded
     * protection state yet". During service/process startup we fail closed
     * until the persisted protection configuration has been loaded.
     */
    @Volatile
    private var protectionSnapshotInitialized = false

    /**
     * Package currently covered by the native privacy barrier.
     *
     * This is separate from PendingLock because the barrier can exist
     * before LockEngine has created the authentication request.
     */
    @Volatile
    private var privacyBarrierPackage: String? = null

    private var gateLaunchElapsed = 0L

    override fun onCreate() {
        super.onCreate()

        runtimeState = ServiceRuntimeState.STARTING

        windowManager =
            getSystemService(WindowManager::class.java)

        instance = this

        // The earliest accessibility event can arrive immediately after the
        // service process is created. Load the durable protection list before
        // relying on the in-memory snapshot for the first-entry decision.
        runCatching {
            app.repository.refreshProtectionState()
            refreshProtectedPackageSnapshot()
        }.onFailure {
            // Keep protectionSnapshotInitialized=false. The event path will
            // fail closed and retry the persistent refresh before allowing
            // any package through.
            android.util.Log.w(
                "AppLockDiag",
                "initial protection snapshot load failed; staying fail-closed",
                it
            )
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        runtimeState = ServiceRuntimeState.RECOVERING
        val generation = ++recoveryGeneration

        android.util.Log.d(
            "AppLockDiag",
            "SERVICE onServiceConnected -> RECOVERING generation=$generation"
        )

        /*
         * Do not request TYPE_VIEW_LONG_CLICKED.
         *
         * Launcher long-press handling generally does not produce the
         * standard accessibility long-click event consistently, while
         * ordinary application long-presses can produce it.
         *
         * Actual uninstall confirmation surfaces are handled separately.
         */
        serviceInfo = serviceInfo.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED

            notificationTimeout = 0L

            flags =
                flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        ForegroundPolicy.refresh(
            this,
            force = true
        )

        hardReset()

        // hardReset() clears only volatile authentication/transition state.
        // The protected-app list itself is durable and must be restored before
        // the service can become READY.
        app.repository.refreshProtectionState()
        refreshProtectedPackageSnapshot()

        AntiTamperManager.enforceStrongProtection(
            this,
            app.repository.protectedPackages()
        )

        // Reconcile immediately AND with a few short retries. On some devices
        // the service reconnects slightly before the system has populated the
        // accessibility window list after screen unlock. A single foreground
        // query can therefore return null even though a protected app is
        // already visible. The retries close that timing window.
        armWatchdog()
        scheduleRecoveryReconciliation(generation)
    }

    /**
     * Rebuilds security state after an AccessibilityService reconnect.
     *
     * We intentionally do not persist the previous authentication request.
     * If the process died while the device was locked/screen-off, a protected
     * app that is visible after reconnect receives a fresh request.
     */
    private fun scheduleRecoveryReconciliation(generation: Long) {
        val delays = longArrayOf(0L, 100L, 300L, 700L, 1_500L)

        delays.forEach { delay ->
            mainHandler.postDelayed({
                if (generation != recoveryGeneration) return@postDelayed
                if (runtimeState != ServiceRuntimeState.RECOVERING) return@postDelayed

                val resolved = runCatching {
                    evaluateCurrentForegroundAfterServiceRecovery()
                }.getOrElse {
                    android.util.Log.w(
                        "AppLockDiag",
                        "SERVICE recovery reconciliation failed",
                        it
                    )
                    false
                }

                // Do not declare READY until at least one reconciliation has
                // completed with protection state loaded. A null foreground
                // is not an unlock decision; later retries/watchdog continue.
                if (resolved) {
                    runtimeState = ServiceRuntimeState.READY
                    android.util.Log.d(
                        "AppLockDiag",
                        "SERVICE READY generation=$generation"
                    )
                }
            }, delay)
        }
    }

    /**
     * Called by LockActivity when the user explicitly backs out/skips the
     * authentication screen.
     *
     * This is intentionally different from onLockActivityDismissed().
     *
     * Dismissed/Stopped can be caused by lifecycle changes, biometric
     * hand-off, SystemUI, rotation, etc. Explicit cancellation is a real
     * user decision and therefore cancels ONLY the exact transaction.
     */
    fun onLockActivityUserCancelled(
        targetPackage: String,
        requestId: Long
    ) {
        mainHandler.post {

            val active = pending

            /*
             * Exact transaction ownership.
             */
            if (
                active == null ||
                active.packageName != targetPackage ||
                active.requestId != requestId
            ) {
                android.util.Log.d(
                    "AppLockDiag",
                    "User cancellation ignored: stale transaction " +
                        "pkg=$targetPackage requestId=$requestId"
                )
                return@post
            }

            /*
             * Cancel ONLY this exact engine request.
             */
            app.lockEngine.cancelRequest(
                targetPackage,
                requestId
            )

            active.launchInFlight = false
            active.lockUiVisible = false
            active.lockUiReady = false
            active.sponsorContinued = false

            provisionalDeparturePackage = null

            mainHandler.removeCallbacks(
                provisionalDepartureRunnable
            )

            clearPending()

            /*
             * IMPORTANT:
             *
             * Never remove the barrier merely because LockActivity was
             * cancelled. The protected application may still be underneath it.
             */
            val visible = resolveVisiblePackages()

            val primary =
                visible.windowsPrimary
                    ?: visible.usageStatsPrimary

            android.util.Log.d(
                "AppLockDiag",
                "Explicit cancellation reconciliation: " +
                    "target=$targetPackage " +
                    "requestId=$requestId " +
                    "primary=$primary"
            )

            when {
                /*
                 * Protected target is still foreground.
                 *
                 * Keep the opaque barrier. There is no authentication request
                 * anymore, so the old LockActivity will NOT be relaunched.
                 */
                primary == targetPackage -> {
                    showProtectionOverlay(
                        barrierPackage = targetPackage
                    )

                    /*
                     * Keep watchdog active so a later foreground transition
                     * can reconcile the barrier.
                     */
                    armWatchdog()
                }

                /*
                 * A real foreign application is now foreground.
                 */
                primary != null &&
                    primary != packageName &&
                    !ForegroundPolicy.isLauncher(primary) &&
                    primary != "com.android.systemui" -> {

                    val classification =
                        if (visible.windowsPrimary == primary) {
                            ForegroundPolicy.classify(
                                context = this,
                                packageName = primary,
                                className = visible.windowsPrimaryClass,
                                eventType =
                                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                                ourPackage = packageName
                            )
                        } else {
                            ForegroundPolicy.Surface.APP
                        }

                    if (
                        classification ==
                            ForegroundPolicy.Surface.NON_DEPARTURE
                    ) {
                        /*
                         * System/transient surface. Stay fail-closed.
                         */
                        showProtectionOverlay(
                            barrierPackage = targetPackage
                        )
                        armWatchdog()
                    } else {
                        app.lockEngine.onUserLeftForeground()
                        lastForegroundPackage = primary
                        removeProtectionOverlay()
                        armWatchdog()
                    }
                }

                /*
                 * Launcher/home is a confirmed departure.
                 */
                primary != null &&
                    ForegroundPolicy.isLauncher(primary) -> {

                    app.lockEngine.onUserLeftForeground()
                    lastForegroundPackage = primary

                    removeProtectionOverlay()
                    armWatchdog()
                }

                /*
                 * SystemUI is transient.
                 *
                 * Do NOT remove the barrier merely because SystemUI became
                 * temporarily visible.
                 */
                primary == "com.android.systemui" -> {
                    showProtectionOverlay(
                        barrierPackage = targetPackage
                    )
                    armWatchdog()
                }

                /*
                 * Unknown/null foreground.
                 *
                 * FAIL CLOSED.
                 *
                 * We cannot prove that the protected application has gone
                 * away, therefore the opaque barrier stays attached.
                 */
                else -> {
                    showProtectionOverlay(
                        barrierPackage = targetPackage
                    )

                    lastForegroundPackage = null

                    armWatchdog()

                    android.util.Log.d(
                        "AppLockDiag",
                        "Cancellation foreground uncertain; " +
                            "keeping barrier target=$targetPackage"
                    )
                }
            }
        }
    }

    private fun evaluateCurrentForegroundAfterServiceRecovery(): Boolean {
        if (!ensureProtectionSnapshotInitialized()) {
            /*
             * We cannot safely classify the current foreground until the
             * durable protection configuration is known.
             *
             * Do not create a target-less global barrier here.
             */
            armWatchdog()
            return false
        }

        val visible = resolveVisiblePackages()

        /*
         * Recovery gets a second chance to identify the actual protected
         * foreground. Accessibility can briefly report SystemUI/launcher as
         * the primary window while UsageStats already reports the protected
         * application. If a protected package is visible in either source,
         * prefer that package for the recovery security decision. This closes
         * the exact gap where the app could already be open before the service
         * reconnects and no later app event arrives to trigger the challenge.
         */
        // SECURITY ORDER: a protected package always outranks AppLock's own
        // package and other incidental windows during recovery. A stale
        // LockActivity/SystemUI window must never make recovery conclude that
        // the protected app is not the real foreground owner.
        // Only an actual foreground source may own the security decision.
        // visible.all is a union of incidental accessibility/UsageStats
        // packages and must NEVER promote a stale/background protected app
        // to foreground.
        val pkg = visible.windowsPrimary
            ?: visible.usageStatsPrimary

        // An initialized empty protected-app set is a real configuration
        // state, not an indication that protection is disabled. There is
        // nothing the foreground enforcement path can protect yet, so send
        // the user directly to AppLock's protected-app configuration.
        if (protectedPackageSnapshot.isEmpty()) {
            if (pkg != null && pkg != packageName) {
                redirectToProtectedAppsConfiguration()
            }
            return true
        }

        if (pkg == null) {
            // We know protection state, but the foreground source has not yet
            // caught up. Keep the watchdog alive and retry shortly.
            armWatchdog()
            return false
        }

        android.util.Log.d(
            "AppLockDiag",
            "SERVICE recovery foreground=$pkg protected=${protectedPackageSnapshot.contains(pkg)}"
        )

        if (pkg == packageName) {
            return true
        }

        installEarlyPrivacyBarrierIfRequired(pkg)

        val recoveryClassName =
            if (pkg == visible.windowsPrimary) {
                visible.windowsPrimaryClass
            } else {
                null
            }

        handleForeground(
            pkg = pkg,
            className = recoveryClassName,
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        )

        return true
    }

    // ------------------------------------------------------------- event path

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        val e = event ?: return

        val supported =
            e.eventType ==
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                e.eventType ==
                AccessibilityEvent.TYPE_WINDOWS_CHANGED

        if (!supported) {
            return
        }

        val pkg =
            e.packageName
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: run {
                    armWatchdog()
                    return
                }

        // Once the durable snapshot is known to be genuinely empty, force
        // configuration instead of allowing the user to continue into apps
        // with an AppLock service that has nothing selected to protect.
        // Never redirect while one of our own activities is foreground.
        if (
            pkg != packageName &&
            protectionSnapshotInitialized &&
            protectedPackageSnapshot.isEmpty()
        ) {
            redirectToProtectedAppsConfiguration()
            return
        }

        /*
         * V8 EARLY PRIVACY BARRIER
         *
         * This remains the first meaningful operation for a protected
         * package. It uses only the in-memory snapshot and LockEngine's
         * in-memory authorization state.
         */
        installEarlyPrivacyBarrierIfRequired(pkg)

        handleForeground(
            pkg = pkg,
            className = e.className,
            eventType = e.eventType
        )
    }

    /**
     * Installs the native opaque privacy barrier at the earliest practical
     * point in the accessibility event path.
     *
     * This method intentionally does not create an authentication request.
     * LockEngine remains the sole owner of authentication transactions.
     */
    private fun installEarlyPrivacyBarrierIfRequired(
        pkg: String
    ) {
        if (pkg == packageName) {
            return
        }

        // Do not create a target-less/global barrier while the protected-app
        // snapshot is still loading. Refresh first; if it is still unavailable,
        // leave the current app usable and let recovery/watchdog retry. Once the
        // snapshot is known, only a positively identified protected package can
        // own the sponsor barrier.
        if (!protectionSnapshotInitialized) {
            ensureProtectionSnapshotInitialized()
            if (!protectionSnapshotInitialized) {
                return
            }
        }

        if (!protectedPackageSnapshot.contains(pkg)) {
            return
        }

        if (app.lockEngine.isAuthorizedForLaunch(pkg)) {
            return
        }

        if (
            protectionOverlay != null &&
            privacyBarrierPackage == pkg
        ) {
            return
        }

        val installed =
            showProtectionOverlay(
                barrierPackage = pkg
            )

        if (installed) {
            android.util.Log.d(
                "AppLockDiag",
                "V8 privacy barrier installed pkg=$pkg " +
                    "pendingReqId=${pending?.requestId}"
            )
        }
    }

    /**
     * Refreshes the in-memory protected-package snapshot.
     */
    private fun refreshProtectedPackageSnapshot() {
        runCatching {
            protectedPackageSnapshot =
                app.repository.protectedPackages().toSet()
            protectionSnapshotInitialized = true
        }.onFailure {
            protectionSnapshotInitialized = false
            android.util.Log.w(
                "AppLockDiag",
                "protected-package snapshot refresh failed; staying fail-closed",
                it
            )
        }
    }

    private fun ensureProtectionSnapshotInitialized(): Boolean {
        if (protectionSnapshotInitialized) return true

        runCatching {
            app.repository.refreshProtectionState()
            refreshProtectedPackageSnapshot()
        }.onFailure {
            android.util.Log.w(
                "AppLockDiag",
                "retry protection snapshot refresh failed",
                it
            )
        }

        return protectionSnapshotInitialized
    }

    private fun evaluateCurrentForegroundAfterServiceReady() {
        evaluateCurrentForegroundAfterServiceRecovery()
    }

    // --------------------------------------------------------- configuration redirect

    /**
     * No protected apps is an explicit setup state. Keep the user inside
     * AppLock until at least one app has been selected, rather than silently
     * allowing ordinary apps to run under an enabled-but-unconfigured
     * enforcement service.
     */
    private fun redirectToProtectedAppsConfiguration() {
        if (configurationRedirectInProgress) {
            return
        }

        configurationRedirectInProgress = true

        clearPending()
        provisionalDeparturePackage = null

        mainHandler.removeCallbacks(
            provisionalDepartureRunnable
        )

        app.lockEngine.resetTransitionState()

        removeProtectionOverlay()
        disarmWatchdogIfIdle()

        runCatching {
            startActivity(
                Intent(this, applock.app.MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )

                    putExtra(
                        applock.app.MainActivity.EXTRA_OPEN_PROTECTED_APPS,
                        true
                    )
                }
            )
        }.onFailure {
            configurationRedirectInProgress = false

            android.util.Log.w(
                "AppLockDiag",
                "failed to redirect to protected-app configuration",
                it
            )

            armWatchdog()
        }
    }

    // --------------------------------------------------------- foreground path

    private fun handleForeground(
        pkg: String,
        className: CharSequence?,
        eventType: Int
    ) {
        // Never make a protection decision from an uninitialized snapshot.
        // The early barrier has already been installed before this call.
        if (!ensureProtectionSnapshotInitialized()) {
            // Do not paint a global white screen while the protected-app
            // snapshot is unavailable. Refresh synchronously; if it still
            // cannot be loaded, leave the real app visible and let the next
            // reconciliation retry.
            armWatchdog()
            return
        }

        android.util.Log.d(
            "AppLockDiag",
            "handleForeground pkg=$pkg class=$className eventType=$eventType " +
                "lastForeground=$lastForegroundPackage " +
                "pendingPkg=${pending?.packageName} " +
                "pendingReqId=${pending?.requestId} " +
                "authorizedForLaunch=${app.lockEngine.sessionPackage()}"
        )

        /*
         * V8 PERFORMANCE RULE
         *
         * Ordinary protected-app launches should not enter the expensive
         * management accessibility-tree path.
         *
         * First perform the cheap package-level check. Only if the package
         * is a possible management surface do we inspect the accessibility
         * tree.
         */
        if (pkg != packageName) {
            val managementCandidate =
                AntiTamperPolicy.isManagementSurface(
                    packageName = pkg,
                    eventType = eventType,
                    className = className
                )

            if (managementCandidate) {
                val isManagement =
                    isAppLockManagementTarget(
                        pkg = pkg,
                        className = className
                    )

                android.util.Log.d(
                    "AppLockDiag",
                    "isManagementSurface pkg=$pkg class=$className -> $isManagement " +
                        "(managementGraceActive=" +
                        "${managementAuthorizedUntilElapsed > SystemClock.elapsedRealtime()})"
                )

                if (
                    isManagement &&
                    managementAuthorizedUntilElapsed >
                        SystemClock.elapsedRealtime()
                ) {
                    lastForegroundPackage = pkg
                    return
                }

                if (!isManagement) {
                    managementAuthorizedUntilElapsed = 0L
                }

                if (
                    isManagement &&
                    app.repository.authenticationConfigured()
                ) {
                    handleManagementSurface(pkg)
                    lastForegroundPackage = pkg
                    return
                }
            } else {
                /*
                 * A non-management foreign package cannot legitimately
                 * continue an old management grace period.
                 */
                managementAuthorizedUntilElapsed = 0L
            }
        }

        /*
         * Once a departure candidate exists, raw accessibility events from
         * the old package are not authoritative.
         */
        provisionalDeparturePackage?.let { candidate ->
            val resolved =
                resolveVisiblePackages()

            val primary =
                resolved.windowsPrimary
                    ?: resolved.usageStatsPrimary

            if (
                primary == candidate &&
                pkg == candidate
            ) {
                cancelProvisionalDeparture(candidate)
            } else {
                return
            }
        }

        /*
         * Refresh protection state periodically instead of on every
         * accessibility event.
         *
         * The V8 early barrier has already been installed before reaching
         * this point.
         */
        val nowElapsed =
            SystemClock.elapsedRealtime()

        if (
            nowElapsed - lastProtectionRefreshElapsed >=
                PROTECTION_REFRESH_INTERVAL_MS
        ) {
            lastProtectionRefreshElapsed = nowElapsed

            app.repository.refreshProtectionState()
            refreshProtectedPackageSnapshot()

            AntiTamperManager.enforceStrongProtection(
                this,
                app.repository.protectedPackages()
            )
        }

        val surfaceClassification =
            ForegroundPolicy.classify(
                context = this,
                packageName = pkg,
                className = className,
                eventType = eventType,
                ourPackage = packageName
            )

        android.util.Log.d(
            "AppLockDiag",
            "classify pkg=$pkg class=$className -> $surfaceClassification"
        )

        when (surfaceClassification) {

            ForegroundPolicy.Surface.OUR_SECURITY_UI -> {
                app.lockEngine.onSecurityUiVisible()

                /*
                 * During service recovery, the fact that an AppLock Activity
                 * is visible is not sufficient to prove that it belongs to a
                 * live authentication transaction.
                 */
                val securityRequest = pending

                if (securityRequest != null) {
                    if (securityRequest.lockUiReady) {
                        removeProtectionOverlay()
                    } else {
                        showProtectionOverlay(
                            barrierPackage = securityRequest.packageName
                        )
                    }
                } else {
                    removeProtectionOverlay()
                }

                armWatchdog()
                return
            }

            ForegroundPolicy.Surface.OUR_MAIN_UI -> {
                handleAppLockMainUiShown()
                return
            }

            ForegroundPolicy.Surface.NON_DEPARTURE -> {
                app.lockEngine.onNonDepartureSurface()
                armWatchdog()
                return
            }

            ForegroundPolicy.Surface.DEPARTURE -> {
                onUserLeft(pkg)
                return
            }

            ForegroundPolicy.Surface.APP -> {
                // Continue to ordinary protected-app handling.
            }
        }

        /*
         * Ordinary protected-app transition.
         */
        val previous =
            lastForegroundPackage

        val currentOwner =
            pending?.packageName
                ?: app.lockEngine.sessionPackage()
                ?: previous?.takeIf {
                    app.repository.isProtected(it)
                }

        if (
            currentOwner != null &&
            currentOwner != pkg &&
            pkg != packageName
        ) {
            scheduleProvisionalDepartureConfirmation(currentOwner)

            android.util.Log.d(
                "AppLockDiag",
                "deferred cross-package transition: " +
                    "owner=$currentOwner newPkg=$pkg " +
                    "pendingReqId=${pending?.requestId}"
            )

            return
        }

        if (
            previous != null &&
            previous != pkg &&
            app.repository.isProtected(previous)
        ) {
            // Leaving a protected app is a hard session boundary for the
            // foreground authorization state. Do not carry the previously
            // accepted package into the next launch.
            app.repository.clearUnlock(previous)
        }

        lastForegroundPackage = pkg

        val decision =
            app.lockEngine.onForegroundApp(pkg)

        applyDecision(decision)
    }

    // ------------------------------------------------------- departure path

    private fun onUserLeft(
        pkg: String
    ) {
        /*
         * A live authentication transaction must survive transient launcher,
         * SystemUI, IME, or LockActivity callbacks, but a REAL departure must
         * terminate the transaction.
         */
        val active = pending

        if (
            active != null &&
            app.lockEngine.isRequestActive(
                active.packageName,
                active.requestId
            )
        ) {
            scheduleProvisionalDepartureConfirmation(
                active.packageName
            )

            showProtectionOverlay(
                barrierPackage = active.packageName
            )

            armWatchdog()

            android.util.Log.d(
                "AppLockDiag",
                "provisional departure during active auth " +
                    "owner=${active.packageName} requestId=${active.requestId} " +
                    "eventPkg=$pkg"
            )

            return
        }

        val previous =
            lastForegroundPackage

        val owner =
            pending?.packageName
                ?: app.lockEngine.sessionPackage()
                ?: previous?.takeIf {
                    app.repository.isProtected(it)
                }

        if (
            owner != null &&
            owner != pkg
        ) {
            scheduleProvisionalDepartureConfirmation(owner)
            armWatchdog()

            android.util.Log.d(
                "AppLockDiag",
                "provisional departure: owner=$owner " +
                    "eventPkg=$pkg pendingReqId=${pending?.requestId}"
            )

            return
        }

        finalizeUserLeft(
            pkg = pkg,
            previous = previous
        )
    }

    private fun finalizeUserLeft(
        pkg: String,
        previous: String?
    ) {
        if (
            previous != null &&
            app.repository.isProtected(previous)
        ) {
            app.repository.clearUnlock(previous)
        }

        app.lockEngine.onUserLeftForeground()

        lastForegroundPackage =
            if (ForegroundPolicy.isLauncher(pkg)) {
                pkg
            } else {
                null
            }

        clearPending()

        removeProtectionOverlay()

        disarmWatchdogIfIdle()
    }

    // ------------------------------------------------ provisional departure

    private var provisionalDeparturePackage: String? = null

    private val provisionalDepartureRunnable =
        Runnable {
            finalizeProvisionalDeparture()
        }

    private fun scheduleProvisionalDepartureConfirmation(
        packageName: String
    ) {
        provisionalDeparturePackage =
            packageName

        mainHandler.removeCallbacks(
            provisionalDepartureRunnable
        )

        mainHandler.postDelayed(
            provisionalDepartureRunnable,
            DEPARTURE_CONFIRM_DELAY_MS
        )

        armWatchdog()
    }

    private fun cancelProvisionalDeparture(
        packageName: String
    ) {
        if (
            provisionalDeparturePackage != packageName
        ) {
            return
        }

        provisionalDeparturePackage = null

        mainHandler.removeCallbacks(
            provisionalDepartureRunnable
        )
    }

    private fun finalizeProvisionalDeparture() {
        val owner =
            provisionalDeparturePackage
                ?: return

        provisionalDeparturePackage = null

        val visible =
            resolveVisiblePackages()

        val primary =
            visible.windowsPrimary
                ?: visible.usageStatsPrimary

        if (
            primary == packageName ||
            primary == owner
        ) {
            return
        }

        if (
            primary != null &&
            visible.windowsPrimary == primary &&
            ForegroundPolicy.classify(
                context = this,
                packageName = primary,
                className = visible.windowsPrimaryClass,
                eventType =
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                ourPackage = packageName
            ) == ForegroundPolicy.Surface.NON_DEPARTURE
        ) {
            scheduleProvisionalDepartureConfirmation(owner)
            return
        }

        finalizeUserLeft(
            pkg = primary ?: owner,
            previous = owner
        )
    }

    // --------------------------------------------------------- lock decision

    private fun applyDecision(
        decision: LockEngine.Decision
    ) {
        if (!decision.requireAuth) {
            if (pending != null) {
                clearPending()
            }

            removeProtectionOverlay()

            if (app.lockEngine.hasActiveSession()) {
                armWatchdog()
            } else {
                disarmWatchdogIfIdle()
            }

            return
        }

        val current =
            pending

        /*
         * Same package + same request means the existing challenge is still
         * valid. Never replace it with a second request.
         */
        if (
            current != null &&
            current.packageName == decision.packageName &&
            current.requestId == decision.requestId
        ) {
            if (!current.lockUiVisible) {
                showProtectionOverlay(
                    barrierPackage = current.packageName
                )

                maybeRelaunchLockUi(current)
            }

            armWatchdog()
            return
        }

        /*
         * New authentication request.
         *
         * A different request replaces the service's mirror only because
         * LockEngine has already created and owns the new transaction.
         */
        val newPending =
            PendingLock(
                packageName = decision.packageName,
                requestId = decision.requestId
            )

        pending = newPending

        showProtectionOverlay(
            barrierPackage = decision.packageName
        )

        app.lockEngine.markAuthUiShown()

        launchProtectedAppGate(
            targetPackage = decision.packageName,
            requestId = decision.requestId
        )

        armWatchdog()
    }

    // --------------------------------------------------------- watchdog path

    private val watchdogRunnable =
        object : Runnable {

            override fun run() {
                if (!watchdogArmed) {
                    return
                }

                runCatching {
                    watchdogTick()
                }.onFailure {
                    android.util.Log.w(
                        "AppLockDiag",
                        "watchdogTick failed",
                        it
                    )
                }

                val interval =
                    if (pending != null) {
                        PENDING_WATCHDOG_INTERVAL_MS
                    } else {
                        WATCHDOG_INTERVAL_MS
                    }

                mainHandler.postDelayed(
                    this,
                    interval
                )
            }
        }

    private fun armWatchdog() {
        if (watchdogArmed) {
            return
        }

        watchdogArmed = true

        val interval =
            if (pending != null) {
                PENDING_WATCHDOG_INTERVAL_MS
            } else {
                WATCHDOG_INTERVAL_MS
            }

        mainHandler.postDelayed(
            watchdogRunnable,
            interval
        )
    }

    private fun disarmWatchdogIfIdle() {
        if (
            pending == null &&
            !app.lockEngine.hasActiveSession()
        ) {
            watchdogArmed = false

            mainHandler.removeCallbacks(
                watchdogRunnable
            )
        }
    }

    private fun watchdogTick() {
        // Startup/reconnection is fail-closed. Do not let an empty in-memory
        // snapshot cause the watchdog to hide the barrier and go idle.
        if (!ensureProtectionSnapshotInitialized()) {
            armWatchdog()
            return
        }

        /*
         * While recovering after a process/service restart, never conclude
         * that "no pending request" means "safe".
         *
         * Do not create a target-less global barrier here. Recovery
         * reconciliation installs a barrier after positively identifying a
         * protected foreground.
         */
        if (runtimeState == ServiceRuntimeState.RECOVERING) {
            armWatchdog()
            return
        }

        val provisionalOwner =
            provisionalDeparturePackage

        if (provisionalOwner != null) {
            val visible =
                resolveVisiblePackages()

            val primary =
                visible.windowsPrimary
                    ?: visible.usageStatsPrimary

            if (
                primary == provisionalOwner ||
                primary == packageName
            ) {
                cancelProvisionalDeparture(provisionalOwner)
            }

            return
        }

        /*
         * Pending authentication is transactional.
         */
        val current =
            pending

        if (current != null) {

            /*
             * A pending request does not automatically mean the user left
             * the target. First classify the currently visible package.
             */
            val visibleForPending =
                resolveVisiblePackages()

            val primaryForPending =
                visibleForPending.windowsPrimary
                    ?: visibleForPending.usageStatsPrimary

            if (
                primaryForPending != null &&
                primaryForPending != current.packageName &&
                primaryForPending != packageName
            ) {

                val classification =
                    if (
                        visibleForPending.windowsPrimary ==
                            primaryForPending
                    ) {
                        ForegroundPolicy.classify(
                            context = this,
                            packageName = primaryForPending,
                            className =
                                visibleForPending.windowsPrimaryClass,
                            eventType =
                                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                            ourPackage = packageName
                        )
                    } else {
                        ForegroundPolicy.Surface.APP
                    }

                when {

                    /*
                     * System/transient surface.
                     *
                     * Keep the transaction and barrier alive.
                     */
                    classification ==
                        ForegroundPolicy.Surface.NON_DEPARTURE -> {

                        showProtectionOverlay(
                            barrierPackage = current.packageName
                        )

                        armWatchdog()
                        return
                    }

                    /*
                     * Launcher/home is a confirmed departure.
                     */
                    ForegroundPolicy.isLauncher(
                        primaryForPending
                    ) -> {

                        android.util.Log.d(
                            "AppLockDiag",
                            "confirmed launcher departure during active auth: " +
                                "foreground=$primaryForPending " +
                                "owner=${current.packageName} " +
                                "requestId=${current.requestId}"
                        )

                        app.lockEngine.cancelRequest(
                            current.packageName,
                            current.requestId
                        )

                        clearPending()

                        provisionalDeparturePackage = null

                        mainHandler.removeCallbacks(
                            provisionalDepartureRunnable
                        )

                        removeProtectionOverlay()

                        app.lockEngine.onUserLeftForeground()

                        lastForegroundPackage =
                            primaryForPending

                        disarmWatchdogIfIdle()
                        return
                    }

                    /*
                     * Real foreign application.
                     */
                    else -> {

                        android.util.Log.d(
                            "AppLockDiag",
                            "confirmed app departure during active auth: " +
                                "foreground=$primaryForPending " +
                                "owner=${current.packageName} " +
                                "requestId=${current.requestId}"
                        )

                        app.lockEngine.cancelRequest(
                            current.packageName,
                            current.requestId
                        )

                        clearPending()

                        provisionalDeparturePackage = null

                        mainHandler.removeCallbacks(
                            provisionalDepartureRunnable
                        )

                        removeProtectionOverlay()

                        app.lockEngine.onUserLeftForeground()

                        lastForegroundPackage = null

                        disarmWatchdogIfIdle()
                        return
                    }
                }
            }

            /*
             * An inactive transaction must never be interpreted as safe.
             */
            if (
                !app.lockEngine.isRequestActive(
                    current.packageName,
                    current.requestId
                )
            ) {
                clearPending()

                showProtectionOverlay(
                    barrierPackage = current.packageName
                )

                val visible =
                    resolveVisiblePackages()

                val primary =
                    visible.windowsPrimary
                        ?: visible.usageStatsPrimary

                if (primary == current.packageName) {
                    val decision =
                        app.lockEngine.onForegroundApp(
                            current.packageName
                        )

                    applyDecision(decision)
                } else {
                    armWatchdog()
                }

                return
            }

            if (!current.lockUiVisible) {
                /*
                 * Automatically launch the exact same LockActivity request.
                 */
                showProtectionOverlay(
                    barrierPackage = current.packageName
                )

                maybeRelaunchLockUi(current)

            } else if (!current.lockUiReady) {

                /*
                 * LockActivity exists but has not obtained real focus yet.
                 */
                showProtectionOverlay(
                    barrierPackage = current.packageName
                )

            } else {

                /*
                 * LockActivity is focused and is now the authentication
                 * surface. Never paint the sponsor over it.
                 */
                removeProtectionOverlay()
            }

            return
        }

        /*
         * No pending challenge and no departure candidate.
         */
        val visible =
            resolveVisiblePackages()

        val primary =
            visible.windowsPrimary
                ?: visible.usageStatsPrimary

        /*
         * FINAL FAIL-CLOSED FALLBACK:
         *
         * If Accessibility missed the transition entirely, the watchdog must
         * run the same LockEngine transaction that an Accessibility event
         * would have run.
         */
        if (
            primary != null &&
            primary != packageName &&
            protectedPackageSnapshot.contains(primary) &&
            !app.lockEngine.isAuthorizedForLaunch(primary)
        ) {
            showProtectionOverlay(
                barrierPackage = primary
            )

            handleForeground(
                pkg = primary,
                className = visible.windowsPrimaryClass,
                eventType =
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )

            return
        }

        /*
         * visible.all is informational only.
         *
         * A background/stale protected package must never cause a barrier over
         * an unrelated foreground app.
         */
        if (!app.lockEngine.hasActiveSession()) {
            removeProtectionOverlay()
            disarmWatchdogIfIdle()
        }
    }

    // ----------------------------------------------------- visible packages

    private data class VisiblePackages(
        val all: Set<String>,
        val windowsPrimary: String?,
        val windowsPrimaryClass: String?,
        val usageStatsPrimary: String?
    )

    private fun resolveVisiblePackages(): VisiblePackages {
        val windowPackages =
            linkedSetOf<String>()

        var windowsPrimary: String? = null
        var windowsPrimaryClass: String? = null

        runCatching {
            val list: List<AccessibilityWindowInfo> =
                windows ?: emptyList()

            for (w in list) {
                if (
                    w.type !=
                        AccessibilityWindowInfo.TYPE_APPLICATION
                ) {
                    continue
                }

                val root =
                    w.root
                        ?: continue

                val name =
                    root.packageName
                        ?.toString()

                if (name.isNullOrBlank()) {
                    continue
                }

                windowPackages.add(name)

                if (
                    windowsPrimary == null &&
                    (w.isActive || w.isFocused)
                ) {
                    windowsPrimary = name

                    windowsPrimaryClass =
                        root.className
                            ?.toString()
                }
            }
        }

        val usageStatsPackages =
            runCatching {
                UsageStatsForegroundSource
                    .currentForegroundPackages(this)
            }.getOrDefault(emptySet())

        val usageStatsPrimary =
            usageStatsPackages.firstOrNull {
                it != packageName
            }

        return VisiblePackages(
            all =
                windowPackages +
                    usageStatsPackages,
            windowsPrimary =
                windowsPrimary,
            windowsPrimaryClass =
                windowsPrimaryClass,
            usageStatsPrimary =
                usageStatsPrimary
        )
    }

    // ------------------------------------------------------------ lock UI ops

    private fun maybeRelaunchLockUi(
        current: PendingLock
    ) {
        val now =
            SystemClock.elapsedRealtime()

        if (
            now - current.lastLaunchElapsed <
                ACTIVITY_START_WATCHDOG_MS
        ) {
            return
        }

        if (
            !app.lockEngine.isRequestActive(
                current.packageName,
                current.requestId
            )
        ) {
            clearPending()
            showProtectionOverlay(
                barrierPackage = current.packageName
            )
            return
        }

        /*
         * The sponsor is not an authentication decision and must never be a
         * prerequisite for the real lock UI.
         */
        if (current.launchInFlight) {
            if (
                now - current.lastLaunchElapsed <
                    GATE_RELAUNCH_INTERVAL_MS
            ) {
                return
            }

            current.launchInFlight = false
        }

        launchProtectedAppGate(
            targetPackage = current.packageName,
            requestId = current.requestId
        )
    }

    private fun launchProtectedAppGate(
        targetPackage: String,
        requestId: Long
    ) {
        val current =
            pending
                ?: return

        if (
            current.packageName != targetPackage ||
            current.requestId != requestId
        ) {
            return
        }

        /*
         * Validate the transaction before scheduling the Activity launch.
         */
        if (
            !app.lockEngine.isRequestActive(
                targetPackage,
                requestId
            )
        ) {
            return
        }

        /*
         * A lock Activity is only ever valid for a currently protected target.
         */
        if (!app.repository.isProtected(targetPackage)) {
            clearPending()
            removeProtectionOverlay()
            disarmWatchdogIfIdle()
            return
        }

        if (
            current.lockUiVisible ||
            current.lockUiReady ||
            current.launchInFlight
        ) {
            return
        }

        current.launchInFlight = true

        current.lastLaunchElapsed =
            SystemClock.elapsedRealtime()

        /*
         * Accessibility callbacks are delivered on the main thread.
         */
        val launch =
            Runnable {
                val live =
                    pending
                        ?: return@Runnable

                if (
                    live.packageName != targetPackage ||
                    live.requestId != requestId
                ) {
                    return@Runnable
                }

                if (
                    !app.lockEngine.isRequestActive(
                        targetPackage,
                        requestId
                    ) ||
                    !app.repository.isProtected(targetPackage)
                ) {
                    live.launchInFlight = false

                    clearPending()

                    removeProtectionOverlay()

                    disarmWatchdogIfIdle()

                    return@Runnable
                }

                runCatching {
                    startActivity(
                        Intent(
                            this,
                            LockActivity::class.java
                        ).apply {

                            /*
                             * IMPORTANT:
                             *
                             * Do NOT use SINGLE_TOP here.
                             *
                             * A new authentication transaction must not be
                             * routed into an existing stale LockActivity via
                             * onNewIntent().
                             */
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                    Intent.FLAG_ACTIVITY_NO_ANIMATION
                            )

                            putExtra(
                                LockActivity.EXTRA_PACKAGE_NAME,
                                targetPackage
                            )

                            putExtra(
                                LockActivity.EXTRA_REQUEST_ID,
                                requestId
                            )
                        }
                    )
                }.onFailure {
                    live.launchInFlight = false

                    android.util.Log.w(
                        "AppLockDiag",
                        "V8 failed to launch LockActivity " +
                            "pkg=$targetPackage requestId=$requestId",
                        it
                    )
                }

                armWatchdog()
            }

        /*
         * If this method is ever called off the main thread, marshal to
         * the main thread.
         */
        if (
            Looper.myLooper() ==
                Looper.getMainLooper()
        ) {
            launch.run()
        } else {
            mainHandler.post(launch)
        }
    }

    // ------------------------------------------------------- management gate

    private fun isAppLockManagementTarget(
        pkg: String,
        className: CharSequence?
    ): Boolean {
        val cls =
            className
                ?.toString()
                ?.lowercase()
                .orEmpty()

        val managementClass =
            cls.contains("uninstall") ||
                cls.contains("appinfo") ||
                cls.contains("applicationinfo") ||
                cls.contains("installedapp") ||
                cls.contains("appdetails") ||
                cls.contains("packageinstaller") ||
                cls.contains("packageuninstaller") ||
                cls.contains("manageapplications") ||
                cls.contains("permission") ||
                cls.contains("deviceadmin") ||
                cls.contains("safecenter") ||
                cls.contains("securitycenter") ||
                cls.contains("appmanager")

        val root =
            rootInActiveWindow

        val visibleText =
            buildString {
                append(
                    root?.text
                        ?.toString()
                        .orEmpty()
                )

                append(' ')

                append(
                    root?.contentDescription
                        ?.toString()
                        .orEmpty()
                )

                collectAccessibilityText(
                    root = root,
                    output = this
                )
            }.lowercase()

        val appLabel =
            runCatching {
                packageManager
                    .getApplicationLabel(
                        packageManager.getApplicationInfo(
                            packageName,
                            0
                        )
                    )
                    .toString()
            }.getOrDefault("AppLock")

        val appLabelLower =
            appLabel.lowercase()

        val mentionsAppLock =
            visibleText.contains(appLabelLower) ||
                visibleText.contains(
                    packageName.lowercase()
                ) ||
                visibleText.contains("applock")

        if (!mentionsAppLock) {
            return false
        }

        val managementActionVisible =
            visibleText.contains("uninstall") ||
                visibleText.contains("remove") ||
                visibleText.contains("app info") ||
                visibleText.contains("application info") ||
                visibleText.contains("disable") ||
                visibleText.contains("force stop") ||
                visibleText.contains("clear data") ||
                visibleText.contains("storage") ||
                visibleText.contains("permissions") ||
                visibleText.contains("device admin")

        return if (managementClass) {
            true
        } else {
            managementActionVisible
        }
    }

    private fun collectAccessibilityText(
        root: AccessibilityNodeInfo?,
        output: StringBuilder
    ) {
        if (root == null) {
            return
        }

        for (index in 0 until root.childCount) {
            val child =
                runCatching {
                    root.getChild(index)
                }.getOrNull()
                    ?: continue

            output.append(' ')

            output.append(
                child.text
                    ?.toString()
                    .orEmpty()
            )

            output.append(' ')

            output.append(
                child.contentDescription
                    ?.toString()
                    .orEmpty()
            )

            collectAccessibilityText(
                root = child,
                output = output
            )

            runCatching {
                child.recycle()
            }
        }
    }

    private fun handleManagementSurface(
        targetPackage: String
    ) {
        if (
            AntiTamperManager.consumeManagementAccess(
                targetPackage
            )
        ) {
            managementAuthorizedUntilElapsed =
                SystemClock.elapsedRealtime() +
                    MANAGEMENT_SESSION_TTL_MS

            removeProtectionOverlay()

            clearPending()

            gateLaunchElapsed = 0L

            return
        }

        val hadPendingChallenge =
            pending != null

        app.lockEngine.onUserLeftForeground()

        clearPending()

        provisionalDeparturePackage = null

        mainHandler.removeCallbacks(
            provisionalDepartureRunnable
        )

        if (hadPendingChallenge) {
            gateLaunchElapsed = 0L
        }

        val now =
            SystemClock.elapsedRealtime()

        if (
            now - gateLaunchElapsed <
                GATE_RELAUNCH_INTERVAL_MS
        ) {
            return
        }

        gateLaunchElapsed = now

        showProtectionOverlay(
            barrierPackage = targetPackage
        )

        app.lockEngine.markAuthUiShown()

        mainHandler.post {
            runCatching {
                startActivity(
                    Intent(
                        this,
                        SecurityGateActivity::class.java
                    ).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                        )

                        putExtra(
                            SecurityGateActivity.EXTRA_MANAGEMENT_PACKAGE,
                            targetPackage
                        )
                    }
                )
            }.onFailure {
                removeProtectionOverlay()
            }
        }
    }

    // ----------------------------------------------------------- AppLock UI

    private fun handleAppLockMainUiShown() {
        configurationRedirectInProgress = false

        val previous =
            lastForegroundPackage

        if (
            previous != null &&
            app.repository.isProtected(previous)
        ) {
            val rule =
                app.repository.getSessionRule()

            if (
                rule == SessionRule.AFTER_LEAVING ||
                rule == SessionRule.IMMEDIATELY
            ) {
                app.repository.clearUnlock(previous)
            }
        }

        app.lockEngine.onAppLockVisible()

        lastForegroundPackage = null

        clearPending()

        provisionalDeparturePackage = null

        mainHandler.removeCallbacks(
            provisionalDepartureRunnable
        )

        managementAuthorizedUntilElapsed = 0L

        removeProtectionOverlay()

        disarmWatchdogIfIdle()
    }

    private fun clearPending() {
        pending = null
    }

    // ------------------------------------------------------------- overlay ops

    /**
     * Opaque native protection barrier.
     *
     * PERSISTENT-WINDOW OPTIMIZATION: the overlay's WindowManager window is
     * created ONCE (on first need) and then kept attached for the life of
     * the service. Every subsequent show/hide is a View.visibility toggle
     * on an already-attached window, not a fresh WindowManager.addView()/
     * removeView() call.
     */
    private fun showProtectionOverlay(
        barrierPackage: String? = null
    ): Boolean {
        val overlay =
            ensureBarrierWindowAttached()
                ?: return false

        val wasAlreadyVisible =
            overlay.visibility == View.VISIBLE

        overlay.visibility =
            View.VISIBLE

        if (barrierPackage != null) {
            /*
             * The barrier belongs to the current protected target, not to
             * the first package that happened to install it.
             */
            privacyBarrierPackage =
                barrierPackage
        }

        return !wasAlreadyVisible
    }

    /**
     * Returns the persistent barrier window, creating and attaching it if
     * this is the first time it's needed.
     */
    private fun buildSponsorOverlay(): View {
        val root =
            FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)

                isClickable = true
                isFocusable = false

                visibility = View.GONE

                importantForAccessibility =
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

        val content =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                gravity =
                    Gravity.CENTER

                setPadding(
                    dp(28),
                    dp(24),
                    dp(28),
                    dp(24)
                )
            }

        val logo =
            ImageView(this).apply {
                setImageResource(
                    applock.app.R.drawable.atoolix_logo
                )

                scaleType =
                    ImageView.ScaleType.CENTER_INSIDE

                layoutParams =
                    LinearLayout.LayoutParams(
                        dp(96),
                        dp(96)
                    ).apply {
                        bottomMargin =
                            dp(18)
                    }
            }

        val sponsor =
            TextView(this).apply {
                text = "Sponsored"

                textSize = 13f

                setTextColor(
                    Color.DKGRAY
                )

                gravity =
                    Gravity.CENTER
            }

        val title =
            TextView(this).apply {
                text = "Atoolix"

                textSize = 24f

                setTextColor(
                    Color.BLACK
                )

                gravity =
                    Gravity.CENTER

                setTypeface(
                    typeface,
                    android.graphics.Typeface.BOLD
                )
            }

        val message =
            TextView(this).apply {
                text =
                    "Continue to authenticate and open your protected app."

                textSize = 15f

                setTextColor(
                    Color.DKGRAY
                )

                gravity =
                    Gravity.CENTER

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin =
                            dp(10)

                        bottomMargin =
                            dp(22)
                    }
            }

        val continueButton =
            Button(this).apply {
                text =
                    "Continue to unlock"

                isAllCaps =
                    false

                setOnClickListener {
                    continueToAuthentication()
                }

                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
            }

        content.addView(logo)
        content.addView(sponsor)
        content.addView(title)
        content.addView(message)
        content.addView(continueButton)

        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        return root
    }

    private fun dp(
        value: Int
    ): Int =
        (
            value *
                resources.displayMetrics.density +
                0.5f
            ).toInt()

    private fun ensureBarrierWindowAttached(): View? {
        protectionOverlay?.let {
            return it
        }

        val wm =
            windowManager
                ?: return null

        val overlay =
            buildSponsorOverlay()

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SECURE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            ).apply {
                gravity =
                    Gravity.TOP or
                        Gravity.START
            }

        return runCatching {
            wm.addView(
                overlay,
                params
            )

            protectionOverlay =
                overlay

            overlay
        }.onFailure {
            android.util.Log.w(
                "AppLockDiag",
                "failed to attach persistent barrier window",
                it
            )
        }.getOrNull()
    }

    fun removeProtectionOverlay() {
        privacyBarrierPackage = null

        protectionOverlay?.visibility =
            View.GONE
    }

    /**
     * Fully detaches the barrier window from WindowManager.
     */
    private fun detachBarrierWindowPermanently() {
        val overlay =
            protectionOverlay
                ?: return

        protectionOverlay = null

        privacyBarrierPackage = null

        runCatching {
            windowManager?.removeViewImmediate(
                overlay
            )
        }
    }

    // ------------------------------------------------------------ callbacks

    /**
     * User explicitly accepted the sponsor/transition screen.
     */
    private fun continueToAuthentication() {
        val current =
            pending
                ?: return

        if (
            !app.lockEngine.isRequestActive(
                current.packageName,
                current.requestId
            )
        ) {
            clearPending()

            removeProtectionOverlay()

            return
        }

        current.sponsorContinued =
            true

        android.util.Log.d(
            "AppLockDiag",
            "Sponsor Continue -> launching LockActivity " +
                "pkg=${current.packageName} " +
                "requestId=${current.requestId}"
        )

        launchProtectedAppGate(
            targetPackage =
                current.packageName,
            requestId =
                current.requestId
        )

        armWatchdog()
    }

    fun onLockActivityShown(
        targetPackage: String,
        requestId: Long
    ) {
        val current =
            pending
                ?: return

        if (
            current.packageName != targetPackage ||
            current.requestId != requestId
        ) {
            return
        }

        current.lockUiVisible =
            true

        current.launchInFlight =
            false

        /*
         * Activity existence is not enough to remove the native barrier.
         * Wait for real READY/focus.
         */
    }

    fun onLockActivityReady(
        targetPackage: String,
        requestId: Long
    ) {
        val current =
            pending
                ?: return

        if (
            current.packageName != targetPackage ||
            current.requestId != requestId
        ) {
            return
        }

        /*
         * Configuration can change while the Activity is starting.
         */
        if (!app.repository.isProtected(targetPackage)) {
            clearPending()

            removeProtectionOverlay()

            disarmWatchdogIfIdle()

            return
        }

        /*
         * READY is emitted only after LockActivity has real window focus.
         */
        current.lockUiReady =
            true

        current.lockUiVisible =
            true

        current.launchInFlight =
            false

        android.util.Log.d(
            "AppLockDiag",
            "LockActivity READY accepted " +
                "pkg=$targetPackage requestId=$requestId " +
                "sponsorContinued=${current.sponsorContinued}"
        )

        /*
         * READY from the exact active request is the hand-off point.
         *
         * Authentication is still required.
         */
        removeProtectionOverlay()

        armWatchdog()
    }

    fun onLockActivityDismissed(
        targetPackage: String,
        requestId: Long
    ) {
        val current =
            pending
                ?: return

        if (
            current.packageName != targetPackage ||
            current.requestId != requestId
        ) {
            return
        }

        /*
         * Activity destruction/stop is NOT authentication and is NOT permission
         * to clear the transaction.
         */
        current.lockUiVisible =
            false

        current.lockUiReady =
            false

        val visible =
            resolveVisiblePackages()

        val primary =
            visible.windowsPrimary
                ?: visible.usageStatsPrimary

        if (primary == targetPackage) {
            showProtectionOverlay(
                barrierPackage =
                    targetPackage
            )
        }

        armWatchdog()
    }

    fun onAuthenticationSucceeded(
        targetPackage: String,
        requestId: Long
    ) {
        val current =
            pending

        /*
         * Only the exact live transaction may complete.
         *
         * LockActivity is responsible for consuming the exact one-shot
         * LaunchAuthorization(package, requestId) before calling this.
         */
        if (
            current == null ||
            current.packageName != targetPackage ||
            current.requestId != requestId
        ) {
            android.util.Log.w(
                "AppLockDiag",
                "ignoring stale authentication success " +
                    "pkg=$targetPackage " +
                    "requestId=$requestId " +
                    "activePkg=${current?.packageName} " +
                    "activeReqId=${current?.requestId}"
            )

            return
        }

        clearPending()

        cancelProvisionalDeparture(
            targetPackage
        )

        lastForegroundPackage =
            targetPackage

        /*
         * Authentication has completed successfully.
         *
         * LockActivity has already consumed the exact authorization and is
         * responsible for starting the target application.
         */
        removeProtectionOverlay()

        armWatchdog()
    }

    // ------------------------------------------------------------- lifecycle

    private fun hardReset() {
        mainHandler.removeCallbacksAndMessages(
            null
        )

        watchdogArmed =
            false

        lastForegroundPackage =
            null

        clearPending()

        provisionalDeparturePackage =
            null

        managementAuthorizedUntilElapsed =
            0L

        configurationRedirectInProgress =
            false

        lastProtectionRefreshElapsed =
            0L

        gateLaunchElapsed =
            0L

        removeProtectionOverlay()

        app.lockEngine.resetTransitionState()
    }

    override fun onInterrupt() {
        /*
         * onInterrupt() does not necessarily mean the service has been
         * disconnected.
         */
        ++recoveryGeneration

        hardReset()

        app.repository.refreshProtectionState()

        refreshProtectedPackageSnapshot()

        runtimeState =
            ServiceRuntimeState.READY

        android.util.Log.d(
            "AppLockDiag",
            "SERVICE onInterrupt -> READY after transition reset"
        )
    }

    override fun onUnbind(
        intent: Intent?
    ): Boolean {
        runtimeState =
            ServiceRuntimeState.DISCONNECTED

        ++recoveryGeneration

        android.util.Log.w(
            "AppLockDiag",
            "SERVICE onUnbind -> DISCONNECTED"
        )

        /*
         * Do not persist authentication/authorization across a service
         * disconnect.
         */
        hardReset()

        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        runtimeState =
            ServiceRuntimeState.DISCONNECTED

        ++recoveryGeneration

        mainHandler.removeCallbacksAndMessages(
            null
        )

        watchdogArmed =
            false

        provisionalDeparturePackage =
            null

        detachBarrierWindowPermanently()

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }

    // ---------------------------------------------------------- screen-on recovery

    /**
     * Screen-on is an explicit security reconciliation point.
     */
    private fun reconcileAfterScreenOn() {
        if (!ensureProtectionSnapshotInitialized()) {
            armWatchdog()
            return
        }

        if (protectedPackageSnapshot.isEmpty()) {
            redirectToProtectedAppsConfiguration()
            return
        }

        val visible =
            resolveVisiblePackages()

        val actualForeground =
            visible.windowsPrimary
                ?: visible.usageStatsPrimary

        if (
            actualForeground != null &&
            protectedPackageSnapshot.contains(
                actualForeground
            ) &&
            actualForeground != packageName
        ) {
            showProtectionOverlay(
                barrierPackage =
                    actualForeground
            )

            handleForeground(
                pkg =
                    actualForeground,
                className =
                    visible.windowsPrimaryClass,
                eventType =
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )

            return
        }

        armWatchdog()
    }

    // ------------------------------------------------------------- companion

    companion object {

        const val MANAGEMENT_SESSION_TTL_MS =
            30_000L

        const val ACTIVITY_START_WATCHDOG_MS =
            600L

        const val WATCHDOG_INTERVAL_MS =
            250L

        const val PENDING_WATCHDOG_INTERVAL_MS =
            80L

        const val DEPARTURE_CONFIRM_DELAY_MS =
            450L

        const val PROTECTION_REFRESH_INTERVAL_MS =
            1_000L

        const val GATE_RELAUNCH_INTERVAL_MS =
            1_500L

        @Volatile
        private var instance:
            AppDetectionAccessibilityService? =
            null

        fun releaseForegroundBarrier() {
            instance?.removeProtectionOverlay()
        }

        fun notifyLockActivityShown(
            targetPackage: String,
            requestId: Long
        ) {
            instance?.onLockActivityShown(
                targetPackage,
                requestId
            )
        }

        fun notifyLockActivityReady(
            targetPackage: String,
            requestId: Long
        ) {
            instance?.onLockActivityReady(
                targetPackage,
                requestId
            )
        }

        fun notifyLockActivityDismissed(
            targetPackage: String,
            requestId: Long
        ) {
            instance?.onLockActivityDismissed(
                targetPackage,
                requestId
            )
        }

        /**
         * Explicit user cancellation from LockActivity.
         *
         * This is deliberately separate from "dismissed", because lifecycle
         * dismissal can happen during biometric/SystemUI hand-off.
         */
        fun notifyLockActivityUserCancelled(
            targetPackage: String,
            requestId: Long
        ) {
            instance?.onLockActivityUserCancelled(
                targetPackage,
                requestId
            )
        }

        fun notifyAuthenticationSucceeded(
            targetPackage: String,
            requestId: Long
        ) {
            instance?.onAuthenticationSucceeded(
                targetPackage,
                requestId
            )
        }

        fun notifyAppLockMainUiShown() {
            instance?.handleAppLockMainUiShown()
        }

        /**
         * Called when the screen turns on / device becomes interactive.
         */
        fun notifyScreenOn() {
            instance?.let { service ->

                service.armWatchdog()

                service.mainHandler.post {
                    service.reconcileAfterScreenOn()
                }

                service.mainHandler.postDelayed({
                    service.reconcileAfterScreenOn()
                }, 250L)

                service.mainHandler.postDelayed({
                    service.reconcileAfterScreenOn()
                }, 750L)

                service.mainHandler.postDelayed({
                    service.reconcileAfterScreenOn()
                }, 1500L)
            }
        }

        fun notifyScreenOff() {
            instance?.let { service ->

                service.lastForegroundPackage =
                    null

                service.clearPending()

                service.provisionalDeparturePackage =
                    null

                service.mainHandler.removeCallbacks(
                    service.provisionalDepartureRunnable
                )

                service.app.lockEngine.onScreenOff()

                service.removeProtectionOverlay()

                service.disarmWatchdogIfIdle()
            }
        }
    }
}