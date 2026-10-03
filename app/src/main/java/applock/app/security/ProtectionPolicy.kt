package applock.app.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import applock.app.ui.ProtectionModeState

object ProtectionPolicy {
    data class Status(
        val selectedMode: ProtectionModeState.Mode,
        val deviceAdminActive: Boolean,
        val deviceOwnerActive: Boolean
    ) {
        val enhancedSelected: Boolean
            get() = selectedMode == ProtectionModeState.Mode.ENHANCED
        val enhancedEnforced: Boolean
            get() = enhancedSelected && deviceOwnerActive
        val enhancedNeedsDeviceOwner: Boolean
            get() = enhancedSelected && !deviceOwnerActive
        /** Compatibility alias: Device Admin is NOT sufficient for Enhanced. */
        val enhancedNeedsDeviceAdmin: Boolean
            get() = enhancedNeedsDeviceOwner
    }

    fun deviceAdminComponent(context: Context): ComponentName =
        ComponentName(context, AppLockDeviceAdminReceiver::class.java)

    fun isDeviceAdminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            ?.isAdminActive(deviceAdminComponent(context)) == true

    fun isDeviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            ?.isDeviceOwnerApp(context.packageName) == true

    fun isEnhancedSelected(context: Context): Boolean =
        ProtectionModeState.isEnhanced(context)

    fun status(context: Context): Status = Status(
        selectedMode = ProtectionModeState.get(context),
        deviceAdminActive = isDeviceAdminActive(context),
        deviceOwnerActive = isDeviceOwner(context)
    )

    fun logCurrentProtectionState(context: Context) {
    val dpm =
        context.getSystemService(
            DevicePolicyManager::class.java
        )

    val adminComponent =
        deviceAdminComponent(context)

    val deviceAdminActive =
        try {
            dpm.isAdminActive(adminComponent)
        } catch (t: Throwable) {
            false
        }

    val deviceOwnerActive =
        try {
            dpm.isDeviceOwnerApp(
                context.packageName
            )
        } catch (t: Throwable) {
            false
        }

    val profileOwnerActive =
        try {
            dpm.isProfileOwnerApp(
                context.packageName
            )
        } catch (t: Throwable) {
            false
        }

    android.util.Log.d(
        "AppLockDiag",
        """
        ===== CURRENT PROTECTION STATE =====
        package=${context.packageName}
        selectedMode=${ProtectionModeState.get(context)}
        deviceAdminActive=$deviceAdminActive
        deviceOwnerActive=$deviceOwnerActive
        profileOwnerActive=$profileOwnerActive
        ====================================
        """.trimIndent()
    )
}

    fun selectStandard(context: Context) {
        EnhancedProtectionManager.disable(context)
        ProtectionModeState.set(context, ProtectionModeState.Mode.STANDARD)
    }

    fun selectEnhancedIfAvailable(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        ProtectionModeState.set(context, ProtectionModeState.Mode.ENHANCED)
        return true
    }

    fun isEnhancedEnforced(context: Context): Boolean = status(context).enhancedEnforced
    fun requiresDeviceOwner(context: Context): Boolean = status(context).enhancedNeedsDeviceOwner
    fun requiresEnhancedSetup(context: Context): Boolean = requiresDeviceOwner(context)
}
