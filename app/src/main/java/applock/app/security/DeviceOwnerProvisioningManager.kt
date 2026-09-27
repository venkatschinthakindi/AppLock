package applock.app.security

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Central authority for AppLock's Device Owner / package-suspension capability.
 *
 * IMPORTANT:
 * - Device Admin != Device Owner.
 * - Package suspension requires Device Owner/Profile Owner privileges.
 * - This class never assumes those privileges exist.
 * - All policy operations fail closed and are logged.
 */
object DeviceOwnerProvisioningManager {

    private const val TAG = "AppLockDiag"

    private fun devicePolicyManager(
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
     * True only when AppLock is actually Device Owner.
     */
    fun isDeviceOwner(
        context: Context
    ): Boolean {
        return try {
            devicePolicyManager(context)
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

    /**
     * True when AppLock is Device Owner OR Profile Owner.
     */
    fun isPolicyOwner(
        context: Context
    ): Boolean {
        return try {
            val dpm = devicePolicyManager(context)

            dpm.isDeviceOwnerApp(context.packageName) ||
                dpm.isProfileOwnerApp(context.packageName)
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
     * Returns a concise diagnostic state.
     */
    fun logCurrentState(
        context: Context
    ) {
        try {
            val dpm = devicePolicyManager(context)

            val deviceOwner =
                dpm.isDeviceOwnerApp(context.packageName)

            val profileOwner =
                dpm.isProfileOwnerApp(context.packageName)

            Log.d(
                TAG,
                "DevicePolicy state: " +
                    "package=${context.packageName} " +
                    "deviceOwner=$deviceOwner " +
                    "profileOwner=$profileOwner " +
                    "sdk=${Build.VERSION.SDK_INT}"
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
     * Suspends one protected application.
     *
     * Returns true only if Android accepted the suspension request
     * without reporting the package as failed.
     */
    fun suspendPackage(
        context: Context,
        packageName: String
    ): Boolean {

        if (packageName.isBlank()) {
            Log.w(
                TAG,
                "suspendPackage rejected: blank package"
            )
            return false
        }

        if (!isPolicyOwner(context)) {
            Log.w(
                TAG,
                "suspendPackage rejected: AppLock is not " +
                    "Device/Profile Owner pkg=$packageName"
            )
            return false
        }

        return try {
            val dpm = devicePolicyManager(context)
            val admin = adminComponent(context)

            val failed =
                dpm.setPackagesSuspended(
                    admin,
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

    /**
     * Unsuspends one protected application after successful authentication.
     */
    fun unsuspendPackage(
        context: Context,
        packageName: String
    ): Boolean {

        if (packageName.isBlank()) {
            Log.w(
                TAG,
                "unsuspendPackage rejected: blank package"
            )
            return false
        }

        if (!isPolicyOwner(context)) {
            Log.w(
                TAG,
                "unsuspendPackage rejected: AppLock is not " +
                    "Device/Profile Owner pkg=$packageName"
            )
            return false
        }

        return try {
            val dpm = devicePolicyManager(context)
            val admin = adminComponent(context)

            val failed =
                dpm.setPackagesSuspended(
                    admin,
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

    /**
     * Reads whether Android currently reports this package suspended.
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
     * Re-establishes the desired locked state after process/service
     * recovery.
     */
    fun enforceSuspended(
        context: Context,
        packageName: String
    ): Boolean {

        if (!isPolicyOwner(context)) {
            Log.w(
                TAG,
                "enforceSuspended skipped: no policy-owner authority " +
                    "pkg=$packageName"
            )
            return false
        }

        if (isPackageSuspended(context, packageName)) {
            Log.d(
                TAG,
                "enforceSuspended already active pkg=$packageName"
            )
            return true
        }

        return suspendPackage(
            context,
            packageName
        )
    }
}