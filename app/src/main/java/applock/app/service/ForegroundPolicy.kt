package applock.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager

/**
 * Classifies a foreground window into one of the categories the lock engine
 * understands.
 *
 * Management/uninstall surfaces are deliberately handled before the generic
 * launcher-departure rule when the launcher itself reports a long-click.
 *
 * This is important because a long-press on an application icon can start
 * the launcher-side uninstall/app-management flow. If the launcher is
 * classified as a normal DEPARTURE first, the management event never reaches
 * AntiTamperPolicy and the SecurityGateActivity cannot be shown.
 */
object ForegroundPolicy {

    enum class Surface {
        OUR_MAIN_UI,
        OUR_SECURITY_UI,
        NON_DEPARTURE,
        DEPARTURE,
        APP
    }

    private const val SYSTEM_UI = "com.android.systemui"
    private const val GMS_PACKAGE = "com.google.android.gms"

    /**
     * System components that render over the current app and through which
     * the user cannot normally switch to another application.
     *
     * PackageInstaller and PermissionController are intentionally NOT placed
     * here because they can host actual application-management surfaces.
     */
    private val nonDeparturePackages = setOf(
        "android",
        "com.android.intentresolver",
        "com.android.systemui.dialog",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.google.android.providers.media.module",
        "com.android.providers.media.module",
        "com.android.credentialmanager",
        "com.google.android.as"
    )

    private const val PACKAGE_INSTALLER_1 =
        "com.google.android.packageinstaller"

    private const val PACKAGE_INSTALLER_2 =
        "com.android.packageinstaller"

    private const val PERMISSION_CONTROLLER_1 =
        "com.google.android.permissioncontroller"

    private const val PERMISSION_CONTROLLER_2 =
        "com.android.permissioncontroller"

    /**
     * PackageInstaller can host both installation and uninstallation
     * confirmation surfaces.
     *
     * Therefore it is intentionally routed through APP instead of being
     * trusted as NON_DEPARTURE.
     *
     * AppDetectionAccessibilityService then gives AntiTamperPolicy the
     * opportunity to recognize and authenticate the management surface.
     */
    private val packageInstallerPackages = setOf(
        PACKAGE_INSTALLER_1,
        PACKAGE_INSTALLER_2
    )

    /**
     * PermissionController normally displays transient permission dialogs,
     * which must not cause protected-app sessions to be destroyed.
     *
     * Its other management surfaces are routed through APP so that
     * AntiTamperPolicy can decide whether authentication is required.
     */
    private val permissionControllerPackages = setOf(
        PERMISSION_CONTROLLER_1,
        PERMISSION_CONTROLLER_2
    )

    private val permissionControllerNonDepartureClassHints = listOf(
        "grantpermissionsactivity",
        "grantpermission"
    )

    /**
     * Known transient Google Play Services surfaces.
     *
     * Unknown GMS activities are deliberately not trusted as
     * NON_DEPARTURE.
     */
    private val gmsNonDepartureClassHints = listOf(
        "accountpicker",
        "consent",
        "credential",
        "authzactivity",
        "signinactivity"
    )

    /**
     * SystemUI windows that are overlays rather than a real departure.
     *
     * Recents/overview remains a DEPARTURE because the user can use it to
     * switch applications.
     */
    private val systemUiOverlayClassHints = listOf(
        "volumedialog",
        "volumepanel",
        "volume_dialog",
        "screenshot",
        "toast",
        "media_output",
        "mediaoutput",
        "chooser",
        "dialog",

        // Gesture-navigation transient surfaces.
        "edgeback",
        "backanimation",
        "backgesture",
        "gestureanimation",
        "navigationbar",
        "navigation_bar"
    )

    /**
     * SystemUI/launcher classes associated with Recents / Overview.
     */
    private val recentsClassHints = listOf(
        "recents",
        "recentsactivity",
        "quickstep",
        "taskswitcher",
        "overview"
    )

    @Volatile
    private var launcherPackages: Set<String> = emptySet()

    @Volatile
    private var imePackages: Set<String> = emptySet()

    @Volatile
    private var launchablePackages: Set<String> = emptySet()

    @Volatile
    private var cacheStampMs = 0L

    private const val CACHE_TTL_MS = 300_000L

    fun refresh(
        context: Context,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()

        if (
            !force &&
            now - cacheStampMs < CACHE_TTL_MS &&
            launcherPackages.isNotEmpty()
        ) {
            return
        }

        cacheStampMs = now

        val pm = context.packageManager

        launcherPackages = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_ALL
            )
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())

        imePackages = runCatching {
            context
                .getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.mapNotNull { it.packageName }
                ?.toSet()
                ?: emptySet()
        }.getOrDefault(emptySet())

        launchablePackages = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.MATCH_ALL
            )
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    fun isLauncher(
        packageName: String
    ): Boolean {
        return launcherPackages.contains(packageName)
    }

    fun classify(
        context: Context,
        packageName: String,
        className: CharSequence?,
        eventType: Int,
        ourPackage: String
    ): Surface {

        refresh(context)

        val cls = className
            ?.toString()
            .orEmpty()

        val lower = cls.lowercase()

        /*
         * AppLock's own package.
         *
         * Only MainActivity represents the normal dashboard. Other
         * activities are treated as security UI so they never accidentally
         * destroy the authentication/session state.
         */
        if (packageName == ourPackage) {
            return if (lower.endsWith(".mainactivity")) {
                Surface.OUR_MAIN_UI
            } else {
                Surface.OUR_SECURITY_UI
            }
        }

        /*
         * Input methods are transient surfaces over the current application.
         */
        if (imePackages.contains(packageName)) {
            return Surface.NON_DEPARTURE
        }

        /*
         * SystemUI:
         *
         * - Recents / Overview -> DEPARTURE.
         * - Known transient overlays -> NON_DEPARTURE.
         * - Unknown SystemUI surface -> DEPARTURE (fail closed).
         */
        if (packageName == SYSTEM_UI) {

            if (
                recentsClassHints.any { hint ->
                    lower.contains(hint)
                }
            ) {
                return Surface.DEPARTURE
            }

            if (
                systemUiOverlayClassHints.any { hint ->
                    lower.contains(hint)
                }
            ) {
                return Surface.NON_DEPARTURE
            }

            return Surface.DEPARTURE
        }

        /*
         * A management/uninstall event on the launcher itself no longer
         * needs special handling here: AppDetectionAccessibilityService now
         * checks AntiTamperPolicy.isManagementSurface() -- which already
         * calls AntiTamperPolicy.isLauncherManagementEvent() as part of its
         * own logic -- unconditionally, for every foreign-package event,
         * BEFORE classify() is even called. A launcher event that IS a
         * management event is handled and returned from there; this
         * function is never reached for it. What follows is ordinary
         * launcher navigation only.
         */
        if (launcherPackages.contains(packageName)) {
            return Surface.DEPARTURE
        }

        /*
         * Known transient system packages.
         */
        if (nonDeparturePackages.contains(packageName)) {
            return Surface.NON_DEPARTURE
        }

        /*
         * PackageInstaller hosts both installation and uninstallation
         * confirmation. Do not trust it as NON_DEPARTURE.
         *
         * Route it through APP so AntiTamperPolicy can gate it.
         */
        if (packageInstallerPackages.contains(packageName)) {
            return Surface.APP
        }

        /*
         * PermissionController:
         *
         * Normal runtime permission grant dialog -> NON_DEPARTURE.
         *
         * Other PermissionController activities -> APP so management
         * surfaces can be authenticated.
         */
        if (permissionControllerPackages.contains(packageName)) {
            return if (
                permissionControllerNonDepartureClassHints.any { hint ->
                    lower.contains(hint)
                }
            ) {
                Surface.NON_DEPARTURE
            } else {
                Surface.APP
            }
        }

        /*
         * Google Play Services.
         *
         * Known transient account/consent/sign-in surfaces are
         * NON_DEPARTURE.
         *
         * Unknown GMS surfaces are routed through APP rather than being
         * blindly trusted.
         */
        if (packageName == GMS_PACKAGE) {
            return if (
                gmsNonDepartureClassHints.any { hint ->
                    lower.contains(hint)
                }
            ) {
                Surface.NON_DEPARTURE
            } else {
                Surface.APP
            }
        }

        /*
         * A normal launchable application.
         */
        if (launchablePackages.contains(packageName)) {
            return Surface.APP
        }

        /*
         * Unknown/non-launchable package.
         *
         * Route through APP. The service/lock engine will treat an
         * unprotected package as a session boundary without falsely
         * trusting an unknown package as NON_DEPARTURE.
         */
        return Surface.APP
    }
}