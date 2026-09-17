package applock.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import applock.app.domain.ProtectedApp

/**
 * User-facing installed application catalog used by the Protected Apps UI.
 *
 * Strategy: instead of asking "does this package declare an Activity?"
 * (true for tons of system/service/webview packages that are NOT apps a
 * user would ever open), we ask the same question the home screen /
 * launcher asks: "does this package expose an activity that responds to
 * ACTION_MAIN + CATEGORY_LAUNCHER?" That is the actual definition of
 * "has an icon a user can tap," and it's what excludes things like
 * Android System WebView, telephony/permission services, providers, etc.
 */
object LaunchableAppCatalog {

    fun load(
        context: Context,
        protectedPackages: Set<String>
    ): List<ProtectedApp> {

        val packageManager: PackageManager = context.packageManager
        val ownPackage: String = context.packageName

        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        // This is the same query the launcher/home-screen uses to decide
        // what gets an icon. It naturally excludes services, providers,
        // libraries, and internal-only activities (WebView, SystemUI
        // internals, permission controller trampolines, etc.).
        val resolvedActivities: List<ResolveInfo> = runCatching {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
        }.getOrElse {
            emptyList()
        }

        // A package can expose more than one launcher activity (rare, but
        // happens with multi-entry apps). Dedup by package name.
        val launchablePackageNames: Set<String> = resolvedActivities
            .mapNotNull { resolveInfo: ResolveInfo ->
                resolveInfo.activityInfo?.packageName
            }
            .toSet()

        val result = mutableListOf<ProtectedApp>()

        for (packageName: String in launchablePackageNames) {

            // Never show AppLock itself.
            if (packageName == ownPackage) {
                continue
            }

            val applicationInfo: ApplicationInfo = runCatching {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.GET_META_DATA
                )
            }.getOrNull() ?: continue

            // Ignore disabled/uninstalled application entries.
            if (!applicationInfo.enabled) {
                continue
            }

            if ((applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED) == 0) {
                continue
            }

            val label: String = runCatching {
                packageManager
                    .getApplicationLabel(applicationInfo)
                    .toString()
                    .trim()
            }.getOrDefault(packageName)

            if (label.isBlank()) {
                continue
            }

            result.add(
                ProtectedApp(
                    packageName = packageName,
                    label = label,
                    protected = protectedPackages.contains(packageName)
                )
            )
        }

        return result
            .distinctBy { app: ProtectedApp ->
                app.packageName
            }
            .sortedWith(
                Comparator { first: ProtectedApp, second: ProtectedApp ->
                    val firstLabel: String = first.label.lowercase()
                    val secondLabel: String = second.label.lowercase()

                    val labelResult: Int =
                        firstLabel.compareTo(secondLabel)

                    if (labelResult != 0) {
                        labelResult
                    } else {
                        first.packageName.lowercase()
                            .compareTo(second.packageName.lowercase())
                    }
                }
            )
    }
}