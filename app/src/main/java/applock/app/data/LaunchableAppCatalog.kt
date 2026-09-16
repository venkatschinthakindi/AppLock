package applock.app.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import applock.app.domain.ProtectedApp

/**
 * User-facing installed application catalog used by the Protected Apps UI.
 *
 * Unlike LauncherApps, this does not require an application to expose a
 * launcher icon/activity.
 *
 * Unlike getInstalledApplications() alone, packages that have no activities
 * are excluded because they are normally service/provider/library/framework
 * packages rather than user-facing applications.
 */
object LaunchableAppCatalog {

    fun load(
        context: Context,
        protectedPackages: Set<String>
    ): List<ProtectedApp> {

        val packageManager: PackageManager = context.packageManager
        val ownPackage: String = context.packageName

        val applications: List<ApplicationInfo> = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(
                PackageManager.GET_META_DATA
            )
        }.getOrElse {
            emptyList<ApplicationInfo>()
        }

        val result = mutableListOf<ProtectedApp>()

        for (applicationInfo: ApplicationInfo in applications) {

            val packageName: String = applicationInfo.packageName

            // Never show AppLock itself.
            if (packageName == ownPackage) {
                continue
            }

            // Ignore disabled/uninstalled application entries.
            if (!applicationInfo.enabled) {
                continue
            }

            if ((applicationInfo.flags and ApplicationInfo.FLAG_INSTALLED) == 0) {
                continue
            }

            /*
             * A package without activities is normally a library, provider,
             * service, framework component, or other non-user-facing package.
             *
             * We deliberately do NOT require a launcher activity here,
             * because some legitimate apps are user-facing without exposing
             * themselves through the launcher.
             */
            if (!hasActivity(packageManager, packageName)) {
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

    /**
     * Returns true when the package declares at least one Activity.
     *
     * This is intentionally broader than checking for a launcher activity.
     */
    private fun hasActivity(
        packageManager: PackageManager,
        packageName: String
    ): Boolean {

        val packageInfo: PackageInfo = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_ACTIVITIES
            )
        }.getOrNull() ?: return false

        return !packageInfo.activities.isNullOrEmpty()
    }
}