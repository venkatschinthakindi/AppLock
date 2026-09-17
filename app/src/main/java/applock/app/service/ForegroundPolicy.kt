package applock.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager

/**
 * Classifies a foreground window into one of the categories the lock engine
 * understands.
 *
 * The distinction that matters for security is:
 *
 *   DEPARTURE      the user can reach another app from here (launcher,
 *                  recents, task switcher, any other real app) -> kill session
 *   NON_DEPARTURE  the user is still inside the protected app (IME, permission
 *                  dialog, share sheet, system picker)         -> park session
 *
 * Anything unknown is treated as a DEPARTURE. Fail closed.
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
     * System components that render *over* the current app and through which
     * the user cannot switch apps. Returning from one of these must never
     * re-challenge.
     */
    private val nonDeparturePackages = setOf(
        "android",
        "com.android.intentresolver",
        "com.android.systemui.dialog",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.google.android.providers.media.module",
        "com.android.providers.media.module",
        "com.android.credentialmanager",
        "com.google.android.as"
    )

    /**
     * `com.google.android.gms` is deliberately NOT in [nonDeparturePackages].
     * It hosts a wide range of surfaces beyond brief dialogs -- some GMS
     * modules run full standalone activities that function like separate
     * apps. Blanket-trusting the whole package would let a session survive
     * departing into one of those. Only its known transient dialog/picker
     * classes are trusted; anything else from GMS falls through to the
     * default unknown-package handling (fail closed).
     */
    private val gmsNonDepartureClassHints = listOf(
        "accountpicker", "consent", "credential", "authzactivity",
        "signinactivity" // legacy account chooser / sign-in dialogs
    )

    /**
     * SystemUI windows that are overlays rather than a way out of the app.
     * Everything else from SystemUI (recents, task switcher, unknown windows)
     * is a departure.
     */
    private val systemUiOverlayClassHints = listOf(
        "volumedialog", "volumepanel", "volume_dialog",
        "screenshot", "toast", "media_output", "mediaoutput",
        "chooser", "dialog"
    )

    private val recentsClassHints = listOf(
        "recents", "recentsactivity", "quickstep", "taskswitcher", "overview"
    )

    @Volatile private var launcherPackages: Set<String> = emptySet()
    @Volatile private var imePackages: Set<String> = emptySet()
    @Volatile private var launchablePackages: Set<String> = emptySet()
    @Volatile private var cacheStampMs = 0L

    private const val CACHE_TTL_MS = 300_000L

    fun refresh(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - cacheStampMs < CACHE_TTL_MS && launcherPackages.isNotEmpty()) return
        cacheStampMs = now

        val pm = context.packageManager

        launcherPackages = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_ALL
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())

        imePackages = runCatching {
            context.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.mapNotNull { it.packageName }
                ?.toSet()
                ?: emptySet()
        }.getOrDefault(emptySet())

        launchablePackages = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.MATCH_ALL
            ).mapNotNull { it.activityInfo?.packageName }.toSet()
        }.getOrDefault(emptySet())
    }

    fun isLauncher(packageName: String): Boolean = launcherPackages.contains(packageName)

    fun classify(
        context: Context,
        packageName: String,
        className: CharSequence?,
        eventType: Int,
        ourPackage: String
    ): Surface {
        refresh(context)
        val cls = className?.toString().orEmpty()
        val lower = cls.lowercase()

        if (packageName == ourPackage) {
            // Only an explicit MainActivity window is the AppLock dashboard
            // boundary. Anything else from our own package (including events
            // with no class name) is treated as security UI, which never
            // clears a session or a pending challenge. Fail closed.
            return if (lower.endsWith(".mainactivity")) {
                Surface.OUR_MAIN_UI
            } else {
                Surface.OUR_SECURITY_UI
            }
        }

        // Input methods never move the user out of the app.
        if (imePackages.contains(packageName)) return Surface.NON_DEPARTURE

        if (packageName == SYSTEM_UI) {
            if (recentsClassHints.any { lower.contains(it) }) return Surface.DEPARTURE
            if (systemUiOverlayClassHints.any { lower.contains(it) }) return Surface.NON_DEPARTURE
            // Notification shade / quick settings / unknown SystemUI window:
            // the user can launch anything from there. Fail closed.
            return Surface.DEPARTURE
        }

        // The launcher (and, on most modern devices, the recents/overview UI
        // that lives inside it) is always a departure.
        if (launcherPackages.contains(packageName)) return Surface.DEPARTURE

        if (nonDeparturePackages.contains(packageName)) return Surface.NON_DEPARTURE

        if (packageName == GMS_PACKAGE) {
            return if (gmsNonDepartureClassHints.any { lower.contains(it) }) {
                Surface.NON_DEPARTURE
            } else if (launchablePackages.contains(packageName)) {
                Surface.APP
            } else {
                // An unrecognised GMS surface that isn't independently
                // launchable. Fail closed rather than assume it's benign.
                Surface.APP
            }
        }

        // A real, launchable application.
        if (launchablePackages.contains(packageName)) return Surface.APP

        // Unknown, non-launchable package. The doc-level rule for this whole
        // function is "unknown -> DEPARTURE, fail closed", and this branch
        // must actually honour it for BOTH event types, not just
        // TYPE_WINDOW_STATE_CHANGED. Treating an unknown TYPE_WINDOWS_CHANGED
        // sender as NON_DEPARTURE would let a session survive an unrecognised
        // window taking the foreground and was found to be a real
        // inconsistency between this comment and the implementation.
        //
        // Route it through the engine as Surface.APP rather than the coarser
        // DEPARTURE path: since the package is not on the protected list,
        // evaluateLocked() ends the session exactly as DEPARTURE would, but
        // does it through the same package-aware evaluation as any other app
        // instead of the blunter "user left" handling.
        return Surface.APP
    }
}
