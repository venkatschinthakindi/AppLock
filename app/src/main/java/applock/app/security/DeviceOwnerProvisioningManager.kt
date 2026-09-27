package applock.app.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Coordinates Device Owner provisioning for Enhanced Protection.
 *
 * Device Owner is a device-management state controlled by Android.
 * An ordinary application cannot silently promote itself to Device Owner.
 *
 * Enhanced Protection therefore remains unavailable until Android has
 * actually granted AppLock Device Owner authority.
 */
object DeviceOwnerProvisioningManager {

    private const val TAG = "AppLockDeviceOwner"

    const val REQUEST_CODE = 7101

    /**
     * Returns true when AppLock is currently Device Owner.
     */
    fun isDeviceOwner(
        context: Context
    ): Boolean {
        return ProtectionPolicy.isDeviceOwner(context)
    }

    /**
     * Returns whether Android currently allows managed-device
     * provisioning to be initiated.
     */
    fun isProvisioningAllowed(
        context: Context
    ): Boolean {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false
        }

        val dpm =
            context.getSystemService(
                DevicePolicyManager::class.java
            ) ?: return false

        return runCatching {
            dpm.isProvisioningAllowed(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE
            )
        }.getOrDefault(false)
    }

    /**
     * Starts Android managed-device provisioning when the current
     * device state permits it.
     *
     * This does NOT silently grant Device Owner.
     *
     * On an already provisioned retail device Android will normally
     * reject this because Device Owner provisioning is no longer allowed.
     */
    fun startProvisioning(
        activity: Activity
    ): Boolean {

        if (ProtectionPolicy.isDeviceOwner(activity)) {
            Log.d(
                TAG,
                "AppLock is already Device Owner"
            )

            return true
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(
                TAG,
                "Device Owner provisioning requires Android 7.0+"
            )

            return false
        }

        val dpm =
            activity.getSystemService(
                DevicePolicyManager::class.java
            ) ?: return false

        val provisioningAllowed =
            runCatching {
                dpm.isProvisioningAllowed(
                    DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE
                )
            }.getOrDefault(false)

        if (!provisioningAllowed) {

            Log.w(
                TAG,
                "Device Owner provisioning is not allowed " +
                    "on the current device"
            )

            return false
        }

        val provisioningIntent =
            Intent(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE
            )

        provisioningIntent.putExtra(
            DevicePolicyManager
                .EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
            ProtectionPolicy.deviceAdminComponent(activity)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            provisioningIntent.putExtra(
                DevicePolicyManager
                    .EXTRA_PROVISIONING_SKIP_ENCRYPTION,
                false
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {

            provisioningIntent.putExtra(
                DevicePolicyManager
                    .EXTRA_PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED,
                true
            )
        }

        return runCatching {

            activity.startActivityForResult(
                provisioningIntent,
                REQUEST_CODE
            )

            Log.d(
                TAG,
                "Device Owner provisioning flow started"
            )

            true

        }.onFailure { error ->

            Log.e(
                TAG,
                "Unable to start Device Owner provisioning",
                error
            )

        }.isSuccess
    }

    /**
     * Handles the result of the provisioning activity.
     *
     * The authoritative result is whether Android actually reports
     * AppLock as Device Owner.
     */
    fun handleProvisioningResult(
        context: Context,
        resultCode: Int
    ): Boolean {

        val deviceOwner =
            ProtectionPolicy.isDeviceOwner(context)

        Log.d(
            TAG,
            "Provisioning finished: " +
                "resultCode=$resultCode " +
                "deviceOwner=$deviceOwner"
        )

        return deviceOwner
    }
}