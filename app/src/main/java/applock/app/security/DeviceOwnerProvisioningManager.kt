package applock.app.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object DeviceOwnerProvisioningManager {

    private const val TAG = "AppLockDeviceOwner"

    const val REQUEST_CODE_PROVISIONING = 4101

    const val ACTION_PROVISION_MANAGED_DEVICE =
        "android.app.action.PROVISION_MANAGED_DEVICE"

    private fun getDevicePolicyManager(
        context: Context
    ): DevicePolicyManager {
        return context.getSystemService(
            DevicePolicyManager::class.java
        )
    }

    fun getAdminComponent(
        context: Context
    ): ComponentName {
        return ComponentName(
            context,
            AppLockDeviceAdminReceiver::class.java
        )
    }

    fun isDeviceOwner(
        context: Context
    ): Boolean {
        return try {
            getDevicePolicyManager(context)
                .isDeviceOwnerApp(context.packageName)
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "Unable to determine Device Owner state",
                t
            )
            false
        }
    }

    fun isDeviceAdmin(
        context: Context
    ): Boolean {
        return try {
            getDevicePolicyManager(context)
                .isAdminActive(
                    getAdminComponent(context)
                )
        } catch (t: Throwable) {
            Log.w(
                TAG,
                "Unable to determine Device Admin state",
                t
            )
            false
        }
    }

    /**
     * Logs the complete current provisioning/security state.
     *
     * MainActivity calls this during startup so we can distinguish:
     * - Device Owner
     * - Device Admin
     * - neither
     */
    fun logCurrentState(
        context: Context
    ) {
        val deviceOwner = isDeviceOwner(context)
        val deviceAdmin = isDeviceAdmin(context)

        Log.i(
            TAG,
            "Current state: " +
                "package=${context.packageName}, " +
                "deviceOwner=$deviceOwner, " +
                "deviceAdmin=$deviceAdmin"
        )
    }

    fun canStartProvisioning(
        context: Context
    ): Boolean {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.LOLLIPOP
        ) {
            return false
        }

        if (isDeviceOwner(context)) {
            return false
        }

        val intent = Intent(
            ACTION_PROVISION_MANAGED_DEVICE
        ).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                getAdminComponent(context)
            )
        }

        return intent.resolveActivity(
            context.packageManager
        ) != null
    }

    fun startProvisioning(
        activity: Activity
    ): Boolean {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.LOLLIPOP
        ) {
            Log.w(
                TAG,
                "Device Owner provisioning requires Android 5.0+"
            )
            return false
        }

        if (isDeviceOwner(activity)) {
            Log.d(
                TAG,
                "Application is already Device Owner"
            )
            return false
        }

        val intent = Intent(
            ACTION_PROVISION_MANAGED_DEVICE
        ).apply {
            putExtra(
                DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                getAdminComponent(activity)
            )
        }

        if (
            intent.resolveActivity(
                activity.packageManager
            ) == null
        ) {
            Log.w(
                TAG,
                "No Device Owner provisioning activity available"
            )
            return false
        }

        return try {
            Log.i(
                TAG,
                "Starting Device Owner provisioning"
            )

            activity.startActivityForResult(
                intent,
                REQUEST_CODE_PROVISIONING
            )

            true
        } catch (t: Throwable) {
            Log.e(
                TAG,
                "Unable to start Device Owner provisioning",
                t
            )
            false
        }
    }

    fun handleProvisioningResult(
        context: Context,
        requestCode: Int
    ): Boolean {

        if (
            requestCode !=
            REQUEST_CODE_PROVISIONING
        ) {
            return false
        }

        val owner = isDeviceOwner(context)

        Log.i(
            TAG,
            "Provisioning result: " +
                "isDeviceOwner=$owner"
        )

        return owner
    }
}