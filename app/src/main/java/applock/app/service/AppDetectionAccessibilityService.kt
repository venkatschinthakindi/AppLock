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
 * Management/uninstall authentication (AntiTamperPolicy.isManagementSurface)
 * is checked UNCONDITIONALLY, first, for every foreign-package event -- see
 * the comment at the top of handleForeground(). It never goes through the
 * DEPARTURE/NON_DEPARTURE/APP classification used for ordinary protected-app
 * detection, so no future addition to that classification can accidentally
 * short-circuit it again.
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
         * TYPE_VIEW_LONG_CLICKED was tried here for launcher-side long-press
         * uninstall detection and removed. Most Android launchers implement
         * icon long-press via custom touch/gesture handling for drag-and-
         * drop rather than the standard View long-click listener mechanism
         * that actually dispatches this accessibility event -- so it very
         * likely never fired for its intended purpose at all. What it DID do
         * is fire for ordinary in-app long-presses (message bubbles, list
         * items, anything using standard long-click handling), adding
         * needless event processing for zero benefit. Uninstall protection
         * does not depend on catching the launcher long-press moment: the
         * actual system uninstall-confirmation dialog is a distinct
         * Activity/window with its own normal window-state-changed event,
         * caught independently of how the user reached it -- see
         * `isManagementSurface` being checked unconditionally, first, below.
         */
        serviceInfo = serviceInfo.apply {

            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED

            notificationTimeout = 0L

            /*
             * Required so getWindows() can report the actual application
             * windows. Only package/window information is used.
             */
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

        /*
         * Only real window-transition events are processed. See the comment
         * in onServiceConnected() for why TYPE_VIEW_LONG_CLICKED was tried
         * and removed.
         */
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
                    /*
                     * A window event without a package cannot safely be
                     * classified here. Let the watchdog resolve the actual
                     * foreground package.
                     */
                    armWatchdog()
                    return
                }

        handleForeground(
            pkg = pkg,
            className = e.className,
            eventType = e.eventType
        )
    }

    private fun handleForeground(
        pkg: String,
        className: CharSequence?,
        eventType: Int
    ) {
        android.util.Log.d(
            "AppLockDiag",
            "handleForeground pkg=$pkg class=$className eventType=$eventType " +
                "lastForeground=$lastForegroundPackage pendingPkg=${pending?.packageName} " +
                "pendingReqId=${pending?.requestId} authorizedForLaunch=" +
                "${app.lockEngine.sessionPackage()}"
        )

        // -----------------------------------------------------------------
        // Management/uninstall/tamper surfaces are checked UNCONDITIONALLY,
        // for every foreign-package event, before anything else -- including
        // before ForegroundPolicy.classify() gets a chance to run at all.
        //
        // This intentionally restores how the app originally worked, before
        // the DEPARTURE/NON_DEPARTURE/APP classification layer existed. That
        // layer fixed real bugs (recents re-entry, stale lock screens,
        // gesture-nav false departures), but it also introduced a NEW class
        // of bug: any package that classify() resolves to NON_DEPARTURE, or
        // that reaches onUserLeft() as an ordinary departure, never reaches
        // the management-surface check below AT ALL, regardless of what
        // AntiTamperPolicy itself says about it. Several rounds of chasing
        // "uninstall still not challenged" turned out to be variants of
        // exactly this: a package correctly listed in
        // AntiTamperPolicy.exactManagementPackages being short-circuited
        // before that list was ever consulted. Checking this first,
        // unconditionally, closes the entire class at once rather than
        // patching one more individual short-circuit.
        // -----------------------------------------------------------------
        if (pkg != packageName) {

            val managementCandidate =
                AntiTamperPolicy.isManagementSurface(
                    packageName = pkg,
                    eventType = eventType,
                    className = className
                )

            /*
             * Management protection is ONLY for AppLock itself.
             *
             * Settings/PackageInstaller/permission-controller are shared
             * Android management surfaces. They must not become an AppLock
             * authentication boundary merely because the user is uninstalling,
             * viewing, disabling, or changing another application.
             *
             * The candidate check above identifies a possible management
             * surface. This second check verifies that the visible management
             * UI is actually acting on AppLock before SecurityGateActivity is
             * allowed to launch.
             */
            val isManagement =
                managementCandidate &&
                    isAppLockManagementTarget(
                        pkg = pkg,
                        className = className
                    )

            android.util.Log.d(
                "AppLockDiag",
                "isManagementSurface pkg=$pkg class=$className -> $isManagement " +
                    "(managementGraceActive=${managementAuthorizedUntilElapsed > SystemClock.elapsedRealtime()})"
            )

            /*
             * A previously authenticated management surface receives a short
             * management session so the OS can complete the requested action
             * without repeatedly launching the gate for every window event.
             */
            if (
                isManagement &&
                managementAuthorizedUntilElapsed > SystemClock.elapsedRealtime()
            ) {
                lastForegroundPackage = pkg
                return
            }

            if (!isManagement) {
                // Returned to an ordinary surface: any temporary management
                // authorization is no longer relevant.
                managementAuthorizedUntilElapsed = 0L
            }

            /*
             * Management/uninstall protection is independent of the
             * Protected Apps list. Once an authentication method is
             * configured, AppLock's own management/uninstall boundary
             * remains protected even if the user has zero other protected
             * applications.
             */
            if (isManagement && app.repository.authenticationConfigured()) {
                handleManagementSurface(pkg)
                lastForegroundPackage = pkg
                return
            }
        }

        /*
         * Once a departure candidate exists, raw Accessibility events from
         * the old package are not authoritative. They can be delayed events
         * from a window that is no longer the user's active app.
         *
         * Only the current OS-resolved primary may cancel the candidate.
         * This prevents an old Telegram event, for example, from cancelling
         * a confirmed move to WhatsApp and restoring Telegram's session.
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
         */
        val nowElapsed =
            SystemClock.elapsedRealtime()

        if (
            nowElapsed - lastProtectionRefreshElapsed >=
                PROTECTION_REFRESH_INTERVAL_MS
        ) {
            lastProtectionRefreshElapsed = nowElapsed

            app.repository.refreshProtectionState()

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

                /*
                 * Our own authentication/security UI must never be treated
                 * as leaving the protected app.
                 */
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

                /*
                 * IME, transient permission UI and other recognized
                 * non-departure surfaces do not terminate the session.
                 */
                app.lockEngine.onNonDepartureSurface()

                armWatchdog()

                return
            }

            ForegroundPolicy.Surface.DEPARTURE -> {

                onUserLeft(pkg)

                return
            }

            ForegroundPolicy.Surface.APP -> {
                // Continue to ordinary protected-app handling below.
            }
        }

        /*
         * Ordinary protected-app transition.
         *
         * If an authentication transaction or authorized session currently
         * owns the foreground, a different application event is only a
         * departure candidate. Do not let one noisy event invalidate the
         * current request/session. The watchdog confirms the departure and
         * then re-evaluates the replacement application from the OS state.
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

        /*
         * A DEPARTURE event is only a candidate. Accessibility can report the
         * launcher/Recents/SystemUI while the same authentication transition
         * is still in progress. Do not mutate LockEngine state here.
         *
         * The candidate is confirmed by the watchdog after a short debounce:
         * - if the protected package becomes primary again, the departure is
         *   cancelled;
         * - if the launcher/another real app remains primary, the session and
         *   pending request are cleared;
         * - the next entry then receives a fresh request.
         */
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

        /*
         * If there is no protected session/challenge to preserve, this is an
         * ordinary departure and can be finalized immediately.
         */
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

        /*
         * Prefer the active accessibility window because UsageStats can lag
         * behind a real task switch by a few hundred milliseconds. UsageStats
         * remains the fallback when no active application window is exposed.
         */
        val primary =
            visible.windowsPrimary
                ?: visible.usageStatsPrimary

        /*
         * If our own security UI is still the active surface, the user is
         * still inside the authentication transaction. Keep it alive.
         */
        if (
            primary == packageName ||
            primary == owner
        ) {
            return
        }

        /*
         * A known transient surface (IME/SystemUI overlay/document picker)
         * is not a real app departure. Keep the session/challenge alive and
         * let the next foreground event cancel the provisional state.
         */
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

        /*
         * No owner window and no replacement foreground app is an actual
         * disappearance (task closed/removed). Treat it as a real boundary.
         * Likewise, a launcher or another launchable application that remains
         * primary means the user has actually left the protected app.
         */
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
                /*
                 * Active sessions must continue to be watched so that a
                 * dropped accessibility event cannot leave the application
                 * permanently unlocked.
                 */
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
            current.packageName ==
                decision.packageName &&
            current.requestId ==
                decision.requestId
        ) {

            if (!current.lockUiVisible) {
                showProtectionOverlay()
                maybeRelaunchLockUi(current)
            }

            armWatchdog()

            return
        }

        /*
         * New authentication request.
         */
        pending =
            PendingLock(
                packageName = decision.packageName,
                requestId = decision.requestId
            )

        showProtectionOverlay()

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

        /*
         * A departure candidate has priority over the normal pending-session
         * watchdog. If the user really left the challenged/authorized app,
         * the candidate must be allowed to expire and clear that state.
         * Until then, competing foreground events are ignored.
         */
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
         * A pending authentication is transactional. While it is active,
         * there is no reason to query UsageStats on every 80ms tick: raw
         * foreground disagreement must not alter the request, and the
         * LockActivity lifecycle callback is the authoritative UI signal.
         *
         * This removes a significant source of OEM-dependent churn and
         * reduces background work while the user is entering a credential.
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
                showProtectionOverlay()
                maybeRelaunchLockUi(current)
            } else {
                removeProtectionOverlay()
            }

            return
        }

        /*
         * No pending challenge and no departure candidate: the watchdog is
         * now only a recovery signal for an already-authorized session or a
         * missed foreground event.
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
                    app.repository.isProtected(pkgName) &&
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
            usageStatsPackages
                .firstOrNull {
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

        current.lastLaunchElapsed =
            SystemClock.elapsedRealtime()

        mainHandler.post {

            val live =
                pending
                    ?: return@post

            if (
                live.packageName != targetPackage ||
                live.requestId != requestId
            ) {
                return@post
            }

            if (
                !app.lockEngine.isRequestActive(
                    targetPackage,
                    requestId
                )
            ) {
                return@post
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
            }

            armWatchdog()
        }
    }

    // ------------------------------------------------------- management gate

    /**
     * Handles AppLock anti-tamper / application-management surfaces.
     *
     * This path is intentionally separate from LockActivity because the user
     * is authenticating an administrative action against AppLock itself,
     * rather than authenticating entry into another protected application.
     */
    /**
     * Returns true only when the visible Android management surface appears
     * to be operating on AppLock itself.
     *
     * We deliberately inspect accessibility text here rather than treating
     * the entire Settings/PackageInstaller package as protected. Otherwise
     * uninstalling or managing ANY application on the device would launch
     * AppLock authentication, which is unrelated to AppLock's own tamper
     * boundary.
     */
    private fun isAppLockManagementTarget(
        pkg: String,
        className: CharSequence?
    ): Boolean {
        val cls = className?.toString()?.lowercase().orEmpty()

        /*
         * Do not gate a generic Settings home/list screen simply because the
         * package is com.android.settings. The target must be an actual
         * application-management surface.
         */
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

        /*
         * Launcher-side long-press menus can have generic class names, so
         * allow them when the accessibility hierarchy itself contains the
         * AppLock label plus a management action.
         */
        val root =
            rootInActiveWindow

        val visibleText =
            buildString {
                append(root?.text?.toString().orEmpty())
                append(' ')
                append(root?.contentDescription?.toString().orEmpty())
                collectAccessibilityText(
                    root = root,
                    output = this
                )
            }.lowercase()

        val appLabel =
            runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(
                        packageName,
                        0
                    )
                ).toString()
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

        /*
         * For a strong management Activity, the target label is sufficient.
         * For a generic launcher/Settings container, require an explicit
         * management action as well to avoid challenging while merely
         * browsing a list of applications.
         */
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
        if (root == null) return

        for (index in 0 until root.childCount) {
            val child =
                runCatching {
                    root.getChild(index)
                }.getOrNull() ?: continue

            output.append(' ')
            output.append(child.text?.toString().orEmpty())
            output.append(' ')
            output.append(child.contentDescription?.toString().orEmpty())

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

        /*
         * A successful management authentication grants a short-lived
         * authorization for this exact management package.
         */
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

        /*
         * Capture this BEFORE clearing it.
         *
         * The old faulty sequence effectively did:
         *
         *     clearPending()
         *     if (pending != null) ...
         *
         * which can never be true.
         *
         * A management surface is its own authentication boundary and must
         * still launch SecurityGateActivity even when another challenge was
         * previously pending.
         */
        val hadPendingChallenge =
            pending != null

        /*
         * The user has crossed into a management surface. Any protected-app
         * session must therefore be invalidated.
         */
        app.lockEngine.onUserLeftForeground()

        clearPending()

        provisionalDeparturePackage = null

        mainHandler.removeCallbacks(
            provisionalDepartureRunnable
        )

        /*
         * If a previous protected-app challenge was pending, allow the
         * management gate to launch immediately rather than being suppressed
         * by the ordinary relaunch throttle.
         */
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

        /*
         * Hide the underlying management screen immediately while the
         * authentication UI is being launched.
         */
        showProtectionOverlay()

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

                /*
                 * If Android rejects the Activity launch, do not leave a
                 * permanent black barrier over the management screen.
                 */
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
     * The opaque barrier protects the underlying protected application while
     * the authentication Activity is being launched.
     *
     * It is deliberately opaque and FLAG_SECURE so protected content is not
     * left visible during the Activity-launch latency window.
     */
    private fun showProtectionOverlay() {

        if (protectionOverlay != null) {
            return
        }

        val wm =
            windowManager
                ?: return

        val overlay =
            FrameLayout(this).apply {

                setBackgroundColor(Color.BLACK)

                isClickable = true

                isFocusable = false

                setOnTouchListener {
                        _: View,
                        _: MotionEvent ->
                    true
                }

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

                gravity =
                    Gravity.TOP or
                        Gravity.START
            }

        runCatching {

            wm.addView(
                overlay,
                params
            )

            protectionOverlay = overlay
        }
    }

    fun removeProtectionOverlay() {

        val overlay =
            protectionOverlay
                ?: return

        protectionOverlay = null

        runCatching {
            windowManager
                ?.removeViewImmediate(overlay)
        }
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
    }

    override fun onDestroy() {

        mainHandler.removeCallbacksAndMessages(null)

        watchdogArmed = false

        provisionalDeparturePackage = null

        removeProtectionOverlay()

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
         * Called when the screen turns off / the device is locked.
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