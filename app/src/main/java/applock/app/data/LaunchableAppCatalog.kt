package applock.app.data

import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import applock.app.domain.ProtectedApp

/**
 * Launcher-visible application catalog.
 *
 * AppLock intentionally does not request QUERY_ALL_PACKAGES. Android's
 * LauncherApps API gives us the applications that can actually be launched
 * from the user's launcher/profile without broad package visibility.
 */
object LaunchableAppCatalog {

    fun load(
        context: Context,
        protectedPackages: Set<String>
    ): List<ProtectedApp> {
        val launcherApps =
            context.getSystemService(LauncherApps::class.java)
                ?: return emptyList()

        val userManager =
            context.getSystemService(UserManager::class.java)

        val users = buildList {
            add(Process.myUserHandle())
            userManager?.userProfiles?.forEach { handle ->
                if (!contains(handle)) add(handle)
            }
        }

        val entries = linkedMapOf<String, LauncherActivityInfo>()

        users.forEach { user ->
            runCatching {
                launcherApps.getActivityList(null, user)
            }.getOrDefault(emptyList()).forEach { info ->
                val packageName = info.applicationInfo.packageName
                if (packageName != context.packageName) {
                    entries.putIfAbsent(packageName, info)
                }
            }
        }

        val pm = context.packageManager

        return entries.keys
            .map { packageName ->
                val label = runCatching {
                    pm.getApplicationLabel(
                        pm.getApplicationInfo(packageName, 0)
                    ).toString()
                }.getOrDefault(packageName)

                ProtectedApp(
                    packageName = packageName,
                    label = label,
                    protected = protectedPackages.contains(packageName)
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
