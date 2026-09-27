package applock.app.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import applock.app.ui.ProtectionModeState

object ProtectionPolicy {

    data class Status(
        val selectedMode: ProtectionModeState.Mode,
        val deviceAdminActive: Boolean
    ) {
        val enhancedSelected: Boolean
            get() =
                selectedMode ==
                    ProtectionModeState.Mode.ENHANCED

        val enhancedEnforced: Boolean
            get() =
                enhancedSelected &&
                    deviceAdminActive

        val enhancedNeedsDeviceAdmin: Boolean
            get() =
                enhancedSelected &&
                    !deviceAdminActive
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
            .getSystemService(
                DevicePolicyManager::class.java
            )
            ?.isAdminActive(
                deviceAdminComponent(context)
            ) == true

    fun status(
        context: Context
    ): Status =
        Status(
            selectedMode =
                ProtectionModeState.get(context),
            deviceAdminActive =
                isDeviceAdminActive(context)
        )

    fun selectStandard(
        context: Context
    ) {
        ProtectionModeState.set(
            context,
            ProtectionModeState.Mode.STANDARD
        )
    }

    fun selectEnhancedIfAvailable(
        context: Context
    ): Boolean {
        if (!isDeviceAdminActive(context)) {
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

    fun requiresDeviceAdminReactivation(
        context: Context
    ): Boolean =
        status(context).enhancedNeedsDeviceAdmin
}