package applock.app.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.app.role.RoleManager
import applock.app.ui.ProtectionModeState

object ProtectionPolicy {

    data class Status(
        val selectedMode: ProtectionModeState.Mode,
        val deviceAdminActive: Boolean,
        val deviceOwnerActive: Boolean,
        val homeRoleHeld: Boolean
    ) {
        val enhancedSelected: Boolean
            get() =
                selectedMode == ProtectionModeState.Mode.ENHANCED

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
         * Kept for compatibility with existing UI.
         *
         * Enhanced Protection requires Device Owner,
         * not merely ordinary Device Admin.
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

    fun isHomeRoleHeld(
        context: Context
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        val roleManager =
            context.getSystemService(
                RoleManager::class.java
            ) ?: return false

        return runCatching {
            roleManager.isRoleHeld(
                RoleManager.ROLE_HOME
            )
        }.getOrDefault(false)
    }

    fun isEnhancedSelected(
        context: Context
    ): Boolean =
        ProtectionModeState.isEnhanced(context)

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

    fun selectEnhancedIfAvailable(
        context: Context
    ): Boolean {
        if (!isDeviceOwner(context)) {
            return false
        }

        if (!isHomeRoleHeld(context)) {
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