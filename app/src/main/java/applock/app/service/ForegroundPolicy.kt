package applock.app.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
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
        "com.google.android.gms",
        "com.android.credentialmanager",
        "com.google.android.as"
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

        // A real, launchable application.
        if (launchablePackages.contains(packageName)) return Surface.APP

        // Unknown non-launchable package. If it is a WINDOW_STATE_CHANGED it
        // took real focus; treat it as an app (fail closed -> session ends).
        return if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Surface.APP
        } else {
            Surface.NON_DEPARTURE
        }
    }
}
