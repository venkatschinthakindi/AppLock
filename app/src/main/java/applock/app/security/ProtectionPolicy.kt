package applock.app.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import applock.app.ui.ProtectionModeState

object ProtectionPolicy {

    data class Status(
        val selectedMode: ProtectionModeState.Mode,
        val deviceAdminActive: Boolean,
        val deviceOwnerActive: Boolean,
        val homeRoleHeld: Boolean
    ) {
        val enhancedSelected: Boolean
            get() = selectedMode == ProtectionModeState.Mode.ENHANCED

        /**
         * Enhanced is OS-enforced only when AppLock is the Device Owner.
         */
        val enhancedEnforced: Boolean
            get() =
                enhancedSelected &&
                    deviceOwnerActive &&
                    homeRoleHeld

        val enhancedNeedsDeviceOwner: Boolean
            get() =
                enhancedSelected &&
                    !deviceOwnerActive

        val enhancedNeedsHomeRole: Boolean
            get() =
                enhancedSelected &&
                    deviceOwnerActive &&
                    !homeRoleHeld

        /**
         * Kept for compatibility with the existing UI.
         */
        val enhancedNeedsDeviceAdmin: Boolean
            get() = enhancedNeedsDeviceOwner
    }

    fun deviceAdminComponent(
        context: Context
    ): ComponentName =
        ComponentName(
            context,
            AppLockDeviceAdminReceiver::class.java
        )

    fun isDeviceAdminActive(
        context: Context
    ): Boolean =
        context
            .getSystemService(DevicePolicyManager::class.java)
            ?.isAdminActive(
                deviceAdminComponent(context)
            ) == true

    fun isDeviceOwner(
        context: Context
    ): Boolean =
        context
            .getSystemService(DevicePolicyManager::class.java)
            ?.isDeviceOwnerApp(
                context.packageName
            ) == true

    fun isEnhancedSelected(
        context: Context
    ): Boolean =
        ProtectionModeState.isEnhanced(context)

    fun isHomeRoleHeld(
        context: Context
    ): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            return false
        }

        val roleManager =
            context.getSystemService(
                android.app.role.RoleManager::class.java
            ) ?: return false

        return runCatching {
            roleManager.isRoleHeld(
                android.app.role.RoleManager.ROLE_HOME
            )
        }.getOrDefault(false)
    }

    fun status(
        context: Context
    ): Status =
        Status(
            selectedMode =
                ProtectionModeState.get(context),
            deviceAdminActive =
                isDeviceAdminActive(context),
            deviceOwnerActive =
                isDeviceOwner(context),
            homeRoleHeld =
                isHomeRoleHeld(context)
        )

    fun selectStandard(
        context: Context
    ) {
        EnhancedProtectionManager.disable(context)

        ProtectionModeState.set(
            context,
            ProtectionModeState.Mode.STANDARD
        )
    }

    /**
     * Enhanced can only be selected after Device Owner + Home role are ready.
     *
     * Ordinary Device Admin is intentionally not accepted as Enhanced.
     */
    fun selectEnhancedIfAvailable(
        context: Context
    ): Boolean {
        val owner = isDeviceOwner(context)
        val home = isHomeRoleHeld(context)

        if (!owner || !home) {
            return false
        }

        ProtectionModeState.set(
            context,
            ProtectionModeState.Mode.ENHANCED
        )

        return true
    }

    fun isEnhancedEnforced(
        context: Context
    ): Boolean =
        status(context).enhancedEnforced

    fun requiresDeviceOwner(
        context: Context
    ): Boolean =
        status(context).enhancedNeedsDeviceOwner

    fun requiresHomeRole(
        context: Context
    ): Boolean =
        status(context).enhancedNeedsHomeRole

    fun requiresEnhancedSetup(
        context: Context
    ): Boolean =
        requiresDeviceOwner(context) ||
            requiresHomeRole(context)
}
