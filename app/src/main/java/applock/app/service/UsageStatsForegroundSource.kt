package applock.app.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * A second, genuinely independent source of "what is actually in the
 * foreground", backed by ActivityManager's own bookkeeping rather than the
 * accessibility service's window list.
 *
 * This exists because the previous watchdog was not actually independent of
 * the primary detection path: both read `AccessibilityService.getWindows()`.
 * On OEMs that throttle, delay, or restrict what a background accessibility
 * service can see (documented behaviour on MIUI, ColorOS, FuntouchOS and
 * similar "battery optimised" builds), the event path AND the windows-based
 * watchdog can both go dark for the same reason at the same time, and a
 * protected app reopened from Recents during that window is never
 * re-evaluated at all.
 *
 * UsageStatsManager draws on a different OS subsystem. It requires the user
 * to explicitly grant "Usage access" (PACKAGE_USAGE_STATS is a special
 * permission with no runtime dialog), so this is used as a corroborating /
 * fallback signal: the app must keep working, and say so honestly, for a
 * user who has not granted it -- never silently degrade protection without
 * surfacing that.
 */
object UsageStatsForegroundSource {

    /** True once the user has granted Settings > Apps > Special access > Usage access. */
    fun isAvailable(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
        }.getOrDefault(AppOpsManager.MODE_ERRORED)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Packages ActivityManager currently considers resumed/foregrounded.
     *
     * On API 29+ this uses ACTIVITY_RESUMED/ACTIVITY_PAUSED, which -- unlike
     * the older MOVE_TO_FOREGROUND/MOVE_TO_BACKGROUND pair -- are reported
     * correctly per-activity in split-screen and multi-window, so more than
     * one package can legitimately come back non-empty.
     *
     * Returns an empty set when usage access is not granted or nothing
     * resolved. Callers must treat "empty" as "unknown", never as "nothing is
     * foregrounded" -- this is a corroborating signal, not the only one.
     */
    fun currentForegroundPackages(context: Context, lookbackMs: Long = 8_000L): Set<String> {
        if (!isAvailable(context)) return emptySet()
        val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptySet()
        val end = System.currentTimeMillis()
        val start = end - lookbackMs

        return runCatching {
            val events = usm.queryEvents(start, end) ?: return@runCatching emptySet<String>()
            val event = UsageEvents.Event()

            val resumedAt = mutableMapOf<String, Long>()
            val pausedAt = mutableMapOf<String, Long>()
            var legacyForegroundPkg: String? = null
            var legacyForegroundTime = Long.MIN_VALUE
            var legacyBackgroundPkg: String? = null
            var legacyBackgroundTime = Long.MIN_VALUE
            var sawModernEvents = false

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        sawModernEvents = true
                        resumedAt[pkg] = event.timeStamp
                    }
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        sawModernEvents = true
                        pausedAt[pkg] = event.timeStamp
                    }
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                        if (event.timeStamp >= legacyForegroundTime) {
                            legacyForegroundTime = event.timeStamp
                            legacyForegroundPkg = pkg
                        }
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        if (event.timeStamp >= legacyBackgroundTime) {
                            legacyBackgroundTime = event.timeStamp
                            legacyBackgroundPkg = pkg
                        }
                    }
                }
            }

            if (sawModernEvents) {
                // A package is "currently resumed" if its last resume is
                // strictly after its last pause (or it has never paused).
                resumedAt.filter { (pkg, resumeTime) ->
                    val pause = pausedAt[pkg]
                    pause == null || resumeTime > pause
                }.keys
            } else {
                val single = if (legacyBackgroundPkg != null &&
                    legacyBackgroundPkg == legacyForegroundPkg &&
                    legacyBackgroundTime > legacyForegroundTime
                ) {
                    null // it went to background after coming forward: it's gone
                } else {
                    legacyForegroundPkg
                }
                setOfNotNull(single)
            }
        }.getOrDefault(emptySet())
    }
}
