package applock.app.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Central authority for Device Owner / package-suspension capability. */
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
        context.getSystemService(DevicePolicyManager::class.java)

    private fun adminComponent(context: Context): ComponentName =
        ComponentName(context, AppLockDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean = try {
        dpm(context).isDeviceOwnerApp(context.packageName)
    } catch (t: Throwable) {
        Log.e(TAG, "Device Owner state check failed", t)
        false
    }

    fun isPolicyOwner(context: Context): Boolean = try {
        val manager = dpm(context)
        manager.isDeviceOwnerApp(context.packageName) ||
            manager.isProfileOwnerApp(context.packageName)
    } catch (t: Throwable) {
        Log.e(TAG, "Policy owner state check failed", t)
        false
    }

    /**
     * Android 12+ uses managed provisioning callbacks instead of allowing an
     * ordinary application to launch ACTION_PROVISION_MANAGED_DEVICE directly.
     * Direct legacy provisioning is therefore intentionally limited to pre-S.
     */
    fun isDirectDeviceOwnerProvisioningAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false
        return try {
            dpm(context).isProvisioningAllowed(
                DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Provisioning availability check failed", t)
            false
        }
    }

    /**
     * Starts legacy managed-device provisioning on Android 11 and below.
     * On Android 12+, Device Owner must be established by the OS-managed
     * provisioning mechanism (QR/NFC/zero-touch/etc.), not by this call.
     */
    fun startDeviceOwnerProvisioning(activity: Activity): ProvisioningStartResult {
        if (isDeviceOwner(activity)) return ProvisioningStartResult.ALREADY_DEVICE_OWNER
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ProvisioningStartResult.NOT_SUPPORTED_ON_THIS_DEVICE
        }
        if (!isDirectDeviceOwnerProvisioningAllowed(activity)) {
            return ProvisioningStartResult.NOT_ALLOWED
        }

        return try {
            val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_DEVICE)
                .putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
                    adminComponent(activity)
                )
            activity.startActivityForResult(
                intent,
                REQUEST_DEVICE_OWNER_PROVISIONING
            )
            ProvisioningStartResult.STARTED
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to start Device Owner provisioning", t)
            ProvisioningStartResult.FAILED
        }
    }

    fun logCurrentState(context: Context) {
        try {
            val manager = dpm(context)
            Log.d(
                TAG,
                "DevicePolicy state: package=${context.packageName} " +
                    "deviceOwner=${manager.isDeviceOwnerApp(context.packageName)} " +
                    "profileOwner=${manager.isProfileOwnerApp(context.packageName)} " +
                    "sdk=${Build.VERSION.SDK_INT}"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to read DevicePolicy state", t)
        }
    }

    fun suspendPackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || !isPolicyOwner(context)) return false
        return try {
            val failed = dpm(context).setPackagesSuspended(
                adminComponent(context), arrayOf(packageName), true
            )
            !failed.contains(packageName)
        } catch (t: Throwable) {
            Log.e(TAG, "suspendPackage failed pkg=$packageName", t)
            false
        }
    }

    fun unsuspendPackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank() || !isPolicyOwner(context)) return false
        return try {
            val failed = dpm(context).setPackagesSuspended(
                adminComponent(context), arrayOf(packageName), false
            )
            !failed.contains(packageName)
        } catch (t: Throwable) {
            Log.e(TAG, "unsuspendPackage failed pkg=$packageName", t)
            false
        }
    }

    fun isPackageSuspended(context: Context, packageName: String): Boolean = try {
        context.packageManager.isPackageSuspended(packageName)
    } catch (t: Throwable) {
        Log.e(TAG, "isPackageSuspended failed pkg=$packageName", t)
        false
    }

    fun enforceSuspended(context: Context, packageName: String): Boolean {
        if (!isPolicyOwner(context)) return false
        if (isPackageSuspended(context, packageName)) return true
        return suspendPackage(context, packageName)
    }
}
