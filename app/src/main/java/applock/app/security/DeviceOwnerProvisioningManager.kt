package applock.app.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Central authority for AppLock Device Owner / package-suspension capability.
 *
 * IMPORTANT:
 *
 * Device Admin != Device Owner.
 *
 * Standard Protection can operate without Device Owner.
 *
 * Enhanced Protection requires actual Device Owner authority because
 * AppLock needs OS-level package suspension to create the stronger
 * protection boundary.
 *
 * Android 12+:
 * Device Owner provisioning is an OS-managed provisioning process.
 * An ordinary already-provisioned consumer device cannot be converted
 * into a Device Owner simply by launching an Activity from the app.
 *
 * The provisioning callback Activities are implemented separately:
 *
 *   ProvisioningModeActivity
 *   PolicyComplianceActivity
 *
 * Those Activities are consumed by Android's managed-provisioning flow.
 *
 * This class therefore NEVER pretends that Device Admin means Device Owner.
 */
object DeviceOwnerProvisioningManager {

    private const val TAG = "AppLockDiag"

    /**
     * Kept for compatibility with existing callers.
     *
     * On Android 12+, there is no normal in-app Activity result flow
     * that can grant Device Owner on an already-provisioned device.
     */
    const val REQUEST_DEVICE_OWNER_PROVISIONING = 4901

    enum class ProvisioningStartResult {
        /**
         * AppLock already owns the device.
         */
        ALREADY_DEVICE_OWNER,

        /**
         * A legacy pre-Android-12 provisioning flow was started.
         */
        STARTED,

        /**
         * Device Owner cannot currently be provisioned through this
         * API path.
         */
        NOT_ALLOWED,

        /**
         * Android 12+ uses OS-managed provisioning instead of the
         * legacy ACTION_PROVISION_MANAGED_DEVICE launch.
         */
        NOT_SUPPORTED_ON_THIS_DEVICE,

        /**
         * Unexpected failure.
         */
        FAILED
    }

    private fun dpm(
        context: Context
    ): DevicePolicyManager {
        return context.getSystemService(
            DevicePolicyManager::class.java
        )
    }

    private fun adminComponent(
        context: Context
    ): ComponentName {
        return ComponentName(
            context,
            AppLockDeviceAdminReceiver::class.java
        )
    }

    /**
     * Returns true only when AppLock is the actual Device Owner.
     */
    fun isDeviceOwner(
        context: Context
    ): Boolean {
        return try {
            dpm(context).isDeviceOwnerApp(
                context.packageName
            )
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Device Owner state check failed",
                t
            )
            false
        }
    }

    /**
     * Returns true when AppLock has either Device Owner or Profile Owner
     * authority.
     *
     * Enhanced Protection currently requires Device Owner specifically.
     */
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
     * Enhanced Protection requires actual Device Owner authority.
     */
    fun isEnhancedAuthorityAvailable(
        context: Context
    ): Boolean {
        return isDeviceOwner(context)
    }

    /**
     * Legacy direct Device Owner provisioning is only applicable
     * before Android 12.
     *
     * Android 12+ requires the OS-managed provisioning flow.
     */
    fun isDirectDeviceOwnerProvisioningAllowed(
        context: Context
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return false
        }

        return try {
            dpm(context).isProvisioningAllowed(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE
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
     * Creates the legacy provisioning Intent for Android 11 and lower.
     *
     * Never use this path on Android 12+.
     */
    fun createLegacyProvisioningIntent(
        context: Context
    ): android.content.Intent? {

        if (isDeviceOwner(context)) {
            Log.d(
                TAG,
                "createLegacyProvisioningIntent: " +
                    "already Device Owner"
            )
            return null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.w(
                TAG,
                "createLegacyProvisioningIntent: " +
                    "Android 12+ uses OS-managed provisioning"
            )
            return null
        }

        if (!isDirectDeviceOwnerProvisioningAllowed(context)) {
            Log.w(
                TAG,
                "createLegacyProvisioningIntent: " +
                    "provisioning is not allowed"
            )
            return null
        }

        return try {
            android.content.Intent(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE
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
                "Unable to create legacy Device Owner " +
                    "provisioning Intent",
                t
            )
            null
        }
    }

    /**
     * Attempts Device Owner provisioning.
     *
     * Android 12+ deliberately does NOT attempt to launch
     * ACTION_PROVISION_MANAGED_DEVICE because Android rejects that
     * provisioning path.
     *
     * On Android 12+, Device Owner must be established through the
     * supported managed-device provisioning mechanism.
     */
    fun startDeviceOwnerProvisioning(
        activity: Activity
    ): ProvisioningStartResult {

        if (isDeviceOwner(activity)) {
            Log.d(
                TAG,
                "startDeviceOwnerProvisioning: " +
                    "already Device Owner"
            )

            return ProvisioningStartResult.ALREADY_DEVICE_OWNER
        }

        /*
         * Android 12 / API 31 and later.
         *
         * Do NOT launch ACTION_PROVISION_MANAGED_DEVICE here.
         *
         * Android documentation explicitly states that using that
         * action to start provisioning on Android 12+ causes
         * provisioning to fail.
         *
         * The OS-managed flow will call our:
         *
         *   ProvisioningModeActivity
         *   PolicyComplianceActivity
         *
         * when the device is actually being provisioned.
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            Log.w(
                TAG,
                "startDeviceOwnerProvisioning: " +
                    "Device Owner is not currently available. " +
                    "Android ${Build.VERSION.SDK_INT} requires " +
                    "OS-managed device provisioning. " +
                    "AppLock cannot self-promote from an already-" +
                    "provisioned device."
            )

            return ProvisioningStartResult
                .NOT_SUPPORTED_ON_THIS_DEVICE
        }

        /*
         * Android 11 and below.
         */
        if (!isDirectDeviceOwnerProvisioningAllowed(activity)) {

            Log.w(
                TAG,
                "startDeviceOwnerProvisioning: " +
                    "legacy provisioning is not allowed"
            )

            return ProvisioningStartResult.NOT_ALLOWED
        }

        val intent =
            createLegacyProvisioningIntent(activity)
                ?: return ProvisioningStartResult.FAILED

        return try {

            Log.d(
                TAG,
                "Starting legacy Device Owner provisioning"
            )

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

    /**
     * Returns a human-readable reason that Enhanced Protection
     * cannot currently be enabled.
     *
     * This is intentionally diagnostic only. UI can use this to
     * explain the state without pretending that Device Admin is
     * equivalent to Device Owner.
     */
    fun getEnhancedProtectionAvailabilityMessage(
        context: Context
    ): String? {

        if (isDeviceOwner(context)) {
            return null
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Enhanced Protection requires AppLock to be provisioned " +
                "by Android as the Device Owner. This cannot be " +
                "enabled from an already-provisioned device."
        } else {
            "Enhanced Protection requires Device Owner provisioning."
        }
    }

    /**
     * Logs the complete DevicePolicy state.
     */
    fun logCurrentState(
        context: Context
    ) {
        try {

            val manager = dpm(context)

            val deviceOwner =
                manager.isDeviceOwnerApp(
                    context.packageName
                )

            val profileOwner =
                manager.isProfileOwnerApp(
                    context.packageName
                )

            val deviceAdmin =
                try {
                    manager.isAdminActive(
                        adminComponent(context)
                    )
                } catch (_: Throwable) {
                    false
                }

            val provisioningAllowed =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    false
                } else {
                    try {
                        manager.isProvisioningAllowed(
                            DevicePolicyManager
                                .ACTION_PROVISION_MANAGED_DEVICE
                        )
                    } catch (_: Throwable) {
                        false
                    }
                }

            Log.d(
                TAG,
                "DevicePolicy state: " +
                    "package=${context.packageName} " +
                    "deviceAdmin=$deviceAdmin " +
                    "deviceOwner=$deviceOwner " +
                    "profileOwner=$profileOwner " +
                    "sdk=${Build.VERSION.SDK_INT} " +
                    "legacyProvisioningAllowed=$provisioningAllowed"
            )

        } catch (t: Throwable) {

            Log.e(
                TAG,
                "Unable to read DevicePolicy state",
                t
            )
        }
    }

    /**
     * Suspends a package at the Android OS policy layer.
     *
     * This is the actual Enhanced Protection primitive.
     *
     * When AppLock is Device Owner, Android can prevent the protected
     * package from being started instead of relying only on the
     * Accessibility foreground-app detection.
     */
    fun suspendPackage(
        context: Context,
        packageName: String
    ): Boolean {

        if (packageName.isBlank()) {
            return false
        }

        /*
         * Enhanced Protection requires Device Owner.
         *
         * Do not silently accept only Device Admin here.
         */
        if (!isDeviceOwner(context)) {

            Log.w(
                TAG,
                "suspendPackage rejected: AppLock is not " +
                    "Device Owner; pkg=$packageName"
            )

            return false
        }

        return try {

            val failedPackages =
                dpm(context).setPackagesSuspended(
                    adminComponent(context),
                    arrayOf(packageName),
                    true
                )

            val success =
                !failedPackages.contains(
                    packageName
                )

            Log.d(
                TAG,
                "suspendPackage pkg=$packageName " +
                    "success=$success " +
                    "failed=${failedPackages.joinToString()}"
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

    /**
     * Removes OS-level package suspension.
     */
    fun unsuspendPackage(
        context: Context,
        packageName: String
    ): Boolean {

        if (packageName.isBlank()) {
            return false
        }

        if (!isDeviceOwner(context)) {

            Log.w(
                TAG,
                "unsuspendPackage rejected: AppLock is not " +
                    "Device Owner; pkg=$packageName"
            )

            return false
        }

        return try {

            val failedPackages =
                dpm(context).setPackagesSuspended(
                    adminComponent(context),
                    arrayOf(packageName),
                    false
                )

            val success =
                !failedPackages.contains(
                    packageName
                )

            Log.d(
                TAG,
                "unsuspendPackage pkg=$packageName " +
                    "success=$success " +
                    "failed=${failedPackages.joinToString()}"
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

    /**
     * Returns whether Android currently reports the package as
     * suspended.
     */
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

    /**
     * Ensures the package is suspended.
     */
    fun enforceSuspended(
        context: Context,
        packageName: String
    ): Boolean {

        if (!isDeviceOwner(context)) {
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