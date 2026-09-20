package applock.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import applock.app.domain.ProtectedApp

/**
 * User-facing installed application catalog used by the Protected Apps UI.
 *
 * Deliberately restricted to apps with a launcher icon -- the exact same
 * query the home screen / app drawer itself uses (`ACTION_MAIN` +
 * `CATEGORY_LAUNCHER`). This used to be broader (any package with at least
 * one declared Activity, launcher icon or not), on the theory that some
 * legitimate apps are user-facing without a launcher icon. In practice that
 * pulled in packages ordinary users don't recognize as "apps" at all --
 * background services, library/helper packages, system components with an
 * incidental settings/about screen -- which is exactly what "showing
 * packages, not apps" describes. A user picking what to protect wants
 * exactly what they see on their home screen and in their app drawer:
 * Telegram, WhatsApp, and so on, nothing else.
 *
 * This also happens to fix a separate, unrelated bug: the previous
 * implementation additionally made a per-package
 * `getPackageInfo(pkg, GET_ACTIVITIES)` Binder call for every installed
 * package to check for that broader "any activity" condition. Apps with
 * unusually large manifests (several major messaging/social apps included)
 * could individually throw `TransactionTooLargeException` on that specific
 * call, silently dropping just that one app from the list. Resolving
 * launcher-visible apps via `queryIntentActivities()` is a single, bulk
 * query resolved from the OS's own intent-resolution table -- there is no
 * per-package call left to fail for one specific app.
 */
object LaunchableAppCatalog {

    fun load(
        context: Context,
        protectedPackages: Set<String>
    ): List<ProtectedApp> {

        val packageManager: PackageManager = context.packageManager
        val ownPackage: String = context.packageName

        val launcherActivities = runCatching {
            packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.MATCH_ALL
            )
        }.getOrDefault(emptyList())

        val result = mutableListOf<ProtectedApp>()
        val seenPackages = mutableSetOf<String>()

        for (resolveInfo in launcherActivities) {
            val activityInfo = resolveInfo.activityInfo ?: continue
            val packageName = activityInfo.packageName

            // Never show AppLock itself.
            if (packageName == ownPackage) continue

            // A package can declare more than one launcher-category
            // activity (alternate icons, work-profile aliases); only list
            // it once.
            if (!seenPackages.add(packageName)) continue

            val applicationInfo: ApplicationInfo = activityInfo.applicationInfo
                ?: runCatching { packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
                ?: continue

            // Ignore disabled/uninstalled application entries.
            if (!applicationInfo.enabled) continue
            if ((applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED) == 0) continue

            val label: String = runCatching {
                resolveInfo.loadLabel(packageManager).toString().trim()
            }.getOrDefault(packageName)

            if (label.isBlank()) continue

            result.add(
                ProtectedApp(
                    packageName = packageName,
                    label = label,
                    protected = protectedPackages.contains(packageName)
                )
            )
        }

        return result
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