package applock.app.security

/**
 * Identifies Android/OEM packages that can expose AppLock uninstall, clear-data,
 * disable-service, or application-management controls.
 *
 * This is deliberately package based because the AccessibilityService is not
 * allowed to read window content in AppLock.
 */
object AntiTamperPolicy {
    private val exactManagementPackages = setOf(
        "com.android.settings",
        "com.google.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.samsung.android.lool",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.miui.securitycore",
        "com.oneplus.security",
        "com.oplus.safecenter",
        "com.coloros.safecenter",
        "com.vivo.security",
        "com.iqoo.secure",
        "com.transsion.phonemaster",
        "com.motorola.security",
        "com.nothing.security"
    )

    fun isManagementPackage(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        if (exactManagementPackages.contains(packageName)) return true

        val lower = packageName.lowercase()
        return lower.contains("packageinstaller") ||
            lower.contains("permissioncontroller") ||
            lower.endsWith(".settings")
    }
}
