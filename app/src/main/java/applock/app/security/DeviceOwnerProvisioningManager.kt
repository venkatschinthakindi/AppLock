package applock.app.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Central authority for AppLock Device Owner / package-suspension capability.
 *
 * IMPORTANT:
 * Device Admin != Device Owner.
 *
 * Enhanced Protection requires actual Device Owner authority.
 */
object DeviceOwnerProvisioningManager {

    private const val TAG = "AppLockDiag"

    const val REQUEST_DEVICE_OWNER_PROVISIONING = 4901

    enum class ProvisioningStartResult {
        ALREADY_DEVICE_OWNER,
        STARTED,
        NOT_ALLOWED,
        NOT_SUPPORTED_ON_THIS_DEVICE,
        FAILED
    }

    private fun dpm(context: Context): DevicePolicyManager =
        context.getSystemService(
            DevicePolicyManager::class.java
        )

    private fun adminComponent(
        context: Context
    ): ComponentName =
        ComponentName(
            context,
            AppLockDeviceAdminReceiver::class.java
        )

    fun isDeviceOwner(
        context: Context
    ): Boolean {
        return try {
            dpm(context)
                .isDeviceOwnerApp(context.packageName)
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Device Owner state check failed",
                t
            )
            false
        }
    }

    fun isPolicyOwner(
        context: Context
    ): Boolean {
        return try {
            val manager = dpm(context)

            manager.isDeviceOwnerApp(
                context.packageName
            ) ||
                manager.isProfileOwnerApp(
                    context.packageName
                )
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Policy owner state check failed",
                t
            )
            false
        }
    }

    /**
     * Direct provisioning is only available through the legacy
     * ACTION_PROVISION_MANAGED_DEVICE path on Android 11 and below.
     *
     * Android 12+ requires the managed provisioning flow using:
     *
     * ACTION_GET_PROVISIONING_MODE
     * ACTION_ADMIN_POLICY_COMPLIANCE
     */
    fun isDirectDeviceOwnerProvisioningAllowed(
        context: Context
    ): Boolean {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {
            return false
        }

        return try {
            dpm(context).isProvisioningAllowed(
                DevicePolicyManager
                    .ACTION_PROVISION_MANAGED_DEVICE
            )
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Provisioning availability check failed",
                t
            )
            false
        }
    }

    /**
     * Creates the legacy provisioning Intent.
     *
     * IMPORTANT:
     * This method DOES NOT start the Intent.
     *
     * AppLockRoot owns the ActivityResult launcher.
     */
    fun createLegacyProvisioningIntent(
        context: Context
    ): Intent? {

        if (isDeviceOwner(context)) {
            return null
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {
            return null
        }

        if (
            !isDirectDeviceOwnerProvisioningAllowed(
                context
            )
        ) {
            return null
        }

        return try {
            Intent(
                DevicePolicyManager
                    .ACTION_PROVISION_MANAGED_DEVICE
            ).apply {

                putExtra(
                    DevicePolicyManager
                        .EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                    adminComponent(context)
                )
            }
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Unable to create Device Owner provisioning Intent",
                t
            )
            null
        }
    }

    /**
     * Legacy pre-Android-12 provisioning entry point.
     *
     * This method starts provisioning exactly once.
     *
     * Do not call createLegacyProvisioningIntent() after this method
     * returns STARTED.
     */
    fun startDeviceOwnerProvisioning(
        activity: Activity
    ): ProvisioningStartResult {

        if (isDeviceOwner(activity)) {
            return ProvisioningStartResult
                .ALREADY_DEVICE_OWNER
        }

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {
            return ProvisioningStartResult
                .NOT_SUPPORTED_ON_THIS_DEVICE
        }

        if (
            !isDirectDeviceOwnerProvisioningAllowed(
                activity
            )
        ) {
            return ProvisioningStartResult
                .NOT_ALLOWED
        }

        val intent =
            createLegacyProvisioningIntent(
                activity
            )
                ?: return ProvisioningStartResult
                    .FAILED

        return try {

            activity.startActivityForResult(
                intent,
                REQUEST_DEVICE_OWNER_PROVISIONING
            )

            ProvisioningStartResult.STARTED

        } catch (t: Throwable) {

            Log.e(
                TAG,
                "Unable to start Device Owner provisioning",
                t
            )

            ProvisioningStartResult.FAILED
        }
    }

    fun logCurrentState(
        context: Context
    ) {
        try {

            val manager = dpm(context)

            Log.d(
                TAG,
                "DevicePolicy state: " +
                    "package=${context.packageName} " +
                    "deviceOwner=" +
                    manager.isDeviceOwnerApp(
                        context.packageName
                    ) +
                    " profileOwner=" +
                    manager.isProfileOwnerApp(
                        context.packageName
                    ) +
                    " sdk=${Build.VERSION.SDK_INT}"
            )

        } catch (t: Throwable) {

            Log.e(
                TAG,
                "Unable to read DevicePolicy state",
                t
            )
        }
    }

    fun suspendPackage(
        context: Context,
        packageName: String
    ): Boolean {

        if (packageName.isBlank()) {
            return false
        }

        if (!isPolicyOwner(context)) {
            Log.w(
                TAG,
                "suspendPackage rejected: no policy-owner authority " +
                    "pkg=$packageName"
            )
            return false
        }

        return try {

            val failed =
                dpm(context).setPackagesSuspended(
                    adminComponent(context),
                    arrayOf(packageName),
                    true
                )

            val success =
                !failed.contains(packageName)

            Log.d(
                TAG,
                "suspendPackage pkg=$packageName " +
                    "success=$success " +
                    "failed=${failed.joinToString()}"
            )

            success

        } catch (t: Throwable) {

            Log.e(
                TAG,
                "suspendPackage failed pkg=$packageName",
                t
            )

            false
        }
    }

    fun unsuspendPackage(
        context: Context,
        packageName: String
    ): Boolean {

        if (packageName.isBlank()) {
            return false
        }

        if (!isPolicyOwner(context)) {
            Log.w(
                TAG,
                "unsuspendPackage rejected: no policy-owner authority " +
                    "pkg=$packageName"
            )
            return false
        }

        return try {

            val failed =
                dpm(context).setPackagesSuspended(
                    adminComponent(context),
                    arrayOf(packageName),
                    false
                )

            val success =
                !failed.contains(packageName)

            Log.d(
                TAG,
                "unsuspendPackage pkg=$packageName " +
                    "success=$success " +
                    "failed=${failed.joinToString()}"
            )

            success

        } catch (t: Throwable) {

            Log.e(
                TAG,
                "unsuspendPackage failed pkg=$packageName",
                t
            )

            false
        }
    }

    fun isPackageSuspended(
        context: Context,
        packageName: String
    ): Boolean {

        return try {

            context.packageManager
                .isPackageSuspended(packageName)

        } catch (t: Throwable) {

            Log.e(
                TAG,
                "isPackageSuspended failed pkg=$packageName",
                t
            )

            false
        }
    }

    fun enforceSuspended(
        context: Context,
        packageName: String
    ): Boolean {

        if (!isPolicyOwner(context)) {
            return false
        }

        if (
            isPackageSuspended(
                context,
                packageName
            )
        ) {
            return true
        }

        return suspendPackage(
            context,
            packageName
        )
    }
}