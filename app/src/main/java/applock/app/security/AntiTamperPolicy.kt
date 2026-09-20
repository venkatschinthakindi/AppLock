package applock.app.security

import android.view.accessibility.AccessibilityEvent

object AntiTamperPolicy {
    private val exactManagementPackages = setOf(
        "com.android.settings", "com.google.android.settings",
        "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.samsung.android.lool", "com.samsung.android.settings",
        "com.miui.securitycenter", "com.miui.securitycore", "com.oneplus.security",
        "com.oplus.safecenter", "com.coloros.safecenter", "com.vivo.security",
        "com.iqoo.secure", "com.transsion.phonemaster", "com.motorola.security",
        "com.nothing.security", "com.google.android.apps.wellbeing",
        "com.android.digitalwellbeing", "com.samsung.android.forest",
        "com.samsung.android.app.parentalcare", "com.android.vending",
        "com.sec.android.app.samsungapps", "com.xiaomi.mipicks",
        "com.heytap.market", "com.vivo.appstore", "com.oneplus.market",
        "com.huawei.appmarket"
    )

    private val launcherPackages = setOf(
        "com.android.launcher3", "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher", "com.miui.home",
        "com.mi.android.globallauncher", "com.oppo.launcher",
        "com.coloros.launcher", "com.oneplus.launcher", "com.vivo.launcher",
        "com.huawei.android.launcher", "com.hihonor.android.launcher",
        "com.motorola.launcher3", "com.nothing.launcher", "com.teslacoilsw.launcher"
    )

    private val managementClassHints = listOf(
        "uninstall", "appinfo", "applicationinfo", "manageapplications",
        "installedapp", "packageinstaller", "packageuninstaller", "uninstaller",
        "appdetails", "permission", "securitycenter", "safecenter",
        "digitalwellbeing", "wellbeing", "pause", "disable", "forcestop",
        "force stop", "cleardata", "clear data", "storage"
    )

    fun isManagementPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (exactManagementPackages.contains(packageName)) return true
        val lower = packageName.lowercase()
        return lower.contains("packageinstaller") ||
            lower.contains("permissioncontroller") ||
            lower.contains("digitalwellbeing") ||
            lower.contains("wellbeing") ||
            lower.contains("appstore") ||
            lower.contains("safecenter") ||
            lower.endsWith(".settings")
    }

    fun isLauncherManagementEvent(
        packageName: String,
        eventType: Int,
        className: CharSequence?
    ): Boolean {
        if (!launcherPackages.contains(packageName)) return false
        val cls = className?.toString()?.lowercase().orEmpty()
        return eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
            managementClassHints.any { cls.contains(it) }
    }

    fun isManagementSurface(
        packageName: String,
        eventType: Int,
        className: CharSequence?
    ): Boolean {
        if (isManagementPackage(packageName)) return true
        if (isLauncherManagementEvent(packageName, eventType, className)) return true

        // OEM package-management components are not consistent across Android
        // builds. Only treat a class as management UI when its package itself
        // has a strong package-management identity; this avoids turning normal
        // third-party Activities with names like SettingsActivity into a
        // false uninstall gate.
        val lowerPackage = packageName.lowercase()
        val likelyManagementPackage =
            lowerPackage.contains("packageinstaller") ||
                lowerPackage.contains("packageuninstaller") ||
                lowerPackage.contains("permissioncontroller") ||
                lowerPackage.contains("safecenter") ||
                lowerPackage.contains("securitycenter") ||
                lowerPackage.contains("phonemaster") ||
                lowerPackage.endsWith(".settings") ||
                lowerPackage.endsWith(".appmanager") ||
                lowerPackage.contains("appmanager")

        if (!likelyManagementPackage) return false

        val cls = className?.toString()?.lowercase().orEmpty()
        return managementClassHints.any { cls.contains(it) }
    }
}
