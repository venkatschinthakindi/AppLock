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
import android.view.accessibility.AccessibilityNodeInfo
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

    private val app: AppLockApplication
        get() = application as AppLockApplication

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private data class PendingLock(
        val packageName: String,
        val requestId: Long,
        var lockUiVisible: Boolean = false,
        var lockUiReady: Boolean = false,
        var lastLaunchElapsed: Long = 0L
    )

    @Volatile
    private var pending: PendingLock? = null

    private var lastForegroundPackage: String? = null

    private var managementAuthorizedUntilElapsed = 0L

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

        windowManager =
            getSystemService(WindowManager::class.java)

        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

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

        app.repository.refreshProtectionState()
        refreshProtectedPackageSnapshot()

        AntiTamperManager.enforceStrongProtection(
            this,
            app.repository.protectedPackages()
        )
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
        protectedPackageSnapshot =
            runCatching {
                app.repository.protectedPackages().toSet()
            }.getOrDefault(emptySet())
    }

    // --------------------------------------------------------- foreground path

    private fun handleForeground(
        pkg: String,
        className: CharSequence?,
        eventType: Int
    ) {
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

                if (
                    pending == null ||
                    pending?.lockUiReady == true
                ) {
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
            val rule =
                app.repository.getSessionRule()

            if (
                rule == SessionRule.AFTER_LEAVING ||
                rule == SessionRule.IMMEDIATELY
            ) {
                app.repository.clearUnlock(previous)
            }
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
            val rule =
                app.repository.getSessionRule()

            if (
                rule == SessionRule.AFTER_LEAVING ||
                rule == SessionRule.IMMEDIATELY
            ) {
                app.repository.clearUnlock(previous)
            }
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
         */
        val newPending =
            PendingLock(
                packageName = decision.packageName,
                requestId = decision.requestId
            )

        pending = newPending

        /*
         * Keep the opaque barrier in place.
         */
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
         * Pending authentication is transactional. Do not perform
         * UsageStats resolution on every 80ms pending tick.
         */
        val current =
            pending

        if (current != null) {

            if (
                !app.lockEngine.isRequestActive(
                    current.packageName,
                    current.requestId
                )
            ) {
                clearPending()
                removeProtectionOverlay()
                disarmWatchdogIfIdle()
                return
            }

            if (!current.lockUiVisible) {
                showProtectionOverlay(
                    barrierPackage = current.packageName
                )
                maybeRelaunchLockUi(current)
            } else if (!current.lockUiReady) {
                showProtectionOverlay(
                    barrierPackage = current.packageName
                )
            } else {
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

        val secondaryExposure =
            visible.all.any { pkgName ->
                pkgName.isNotBlank() &&
                    pkgName != packageName &&
                    pkgName != primary &&
                    protectedPackageSnapshot.contains(pkgName) &&
                    !app.lockEngine.isAuthorizedForLaunch(pkgName)
            }

        if (secondaryExposure) {
            showProtectionOverlay()
        } else if (!app.lockEngine.hasActiveSession()) {
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
            removeProtectionOverlay()
            return
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

        current.lastLaunchElapsed =
            SystemClock.elapsedRealtime()

        /*
         * Accessibility callbacks are delivered on the main thread.
         * Keep the actual Activity start on that same thread while
         * retaining the final request validation immediately before launch.
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
                    )
                ) {
                    return@Runnable
                }

                runCatching {
                    startActivity(
                        android.content.Intent(
                            this,
                            LockActivity::class.java
                        ).apply {
                            addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                    android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
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
         * the main thread. In the normal accessibility path it is already
         * on the main thread, so this does not add another post hop.
         */
        if (Looper.myLooper() == Looper.getMainLooper()) {
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
                visibleText.contains(packageName.lowercase()) ||
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
                    android.content.Intent(
                        this,
                        SecurityGateActivity::class.java
                    ).apply {
                        addFlags(
                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION
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
     *
     * This matters because addView() for a new window is a round-trip to
     * WindowManagerService: it negotiates a new window token, inset
     * handling, and a first layout pass before anything can actually draw.
     * A visibility toggle on a window that already exists skips all of
     * that -- it's a local property change plus a relayout/redraw the
     * compositor already has a surface ready for. For a barrier whose
     * entire purpose is "as early as physically possible", removing that
     * round-trip from the per-launch path is a real, measurable win, not
     * a cosmetic one.
     *
     * Honest limit: this cannot make the barrier appear before the
     * accessibility event that triggers it does. AccessibilityEvent
     * delivery is itself a Binder call from system_server into this
     * process, dispatched onto the main looper -- that dispatch latency is
     * an Android platform floor no app-level code can reduce to zero. What
     * this optimization removes is every bit of avoidable latency AFTER
     * the event arrives: no disk/repository access (already true before
     * this change) and now no window-creation IPC either. What's left is
     * essentially just the cost of a View property write and a compositor
     * frame -- as close to the floor as this architecture gets.
     */
    private fun showProtectionOverlay(
        barrierPackage: String? = null
    ): Boolean {
        val overlay = ensureBarrierWindowAttached() ?: return false

        val wasAlreadyVisible = overlay.visibility == View.VISIBLE

        overlay.visibility = View.VISIBLE

        if (barrierPackage != null && privacyBarrierPackage == null) {
            privacyBarrierPackage = barrierPackage
        }

        return !wasAlreadyVisible
    }

    /**
     * Returns the persistent barrier window, creating and attaching it if
     * this is the first time it's needed (or if it was previously torn
     * down, e.g. by onDestroy, or force-removed by the OS -- some OEMs are
     * aggressive about reclaiming overlay windows from backgrounded apps,
     * so this must tolerate finding it gone and recreate it rather than
     * assume it's always still attached).
     */
    private fun ensureBarrierWindowAttached(): View? {
        protectionOverlay?.let { return it }

        val wm = windowManager ?: return null

        val overlay =
            FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
                isClickable = true
                isFocusable = false
                visibility = View.GONE

                setOnTouchListener { _: View, _: MotionEvent -> true }

                importantForAccessibility =
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

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
                gravity = Gravity.TOP or Gravity.START
            }

        return runCatching {
            wm.addView(overlay, params)
            protectionOverlay = overlay
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
        protectionOverlay?.visibility = View.GONE
    }

    /**
     * Fully detaches the barrier window from WindowManager. Only called
     * from onDestroy() -- the persistent-window optimization above means
     * this is intentionally NOT what happens on every ordinary hide.
     */
    private fun detachBarrierWindowPermanently() {
        val overlay = protectionOverlay ?: return
        protectionOverlay = null
        privacyBarrierPackage = null
        runCatching { windowManager?.removeViewImmediate(overlay) }
    }

    // ------------------------------------------------------------ callbacks

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

        current.lockUiVisible = true

        /*
         * Activity existence is not enough to remove the native barrier.
         * Wait for first pre-draw.
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

        current.lockUiReady = true

        /*
         * LockActivity has rendered its first frame.
         */
        removeProtectionOverlay()
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

        clearPending()

        removeProtectionOverlay()

        disarmWatchdogIfIdle()
    }

    fun onAuthenticationSucceeded(
        targetPackage: String,
        requestId: Long
    ) {
        val current =
            pending

        if (
            current != null &&
            current.packageName == targetPackage &&
            current.requestId == requestId
        ) {
            clearPending()
        }

        cancelProvisionalDeparture(targetPackage)

        lastForegroundPackage =
            targetPackage

        removeProtectionOverlay()

        armWatchdog()
    }

    // ------------------------------------------------------------- lifecycle

    private fun hardReset() {
        mainHandler.removeCallbacksAndMessages(null)

        watchdogArmed = false

        lastForegroundPackage = null

        clearPending()

        provisionalDeparturePackage = null

        managementAuthorizedUntilElapsed = 0L

        lastProtectionRefreshElapsed = 0L

        gateLaunchElapsed = 0L

        removeProtectionOverlay()

        app.lockEngine.resetTransitionState()
    }

    override fun onInterrupt() {
        hardReset()

        app.repository.refreshProtectionState()

        refreshProtectedPackageSnapshot()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)

        watchdogArmed = false

        provisionalDeparturePackage = null

        detachBarrierWindowPermanently()

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
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
         * Called when the screen turns off / device is locked.
         */
        fun notifyScreenOff() {
            instance?.let { service ->

                service.lastForegroundPackage = null

                service.clearPending()

                service.provisionalDeparturePackage = null

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

