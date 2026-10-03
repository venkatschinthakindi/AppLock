package applock.app.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Android 12+ managed-device provisioning callback.
 *
 * IMPORTANT:
 * This activity is called by Android's managed-provisioning flow.
 * It does NOT grant Device Owner by itself.
 */
class ProvisioningModeActivity : Activity() {

    companion object {
        private const val TAG = "AppLockDiag"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(
            TAG,
            "ProvisioningModeActivity: received OS provisioning callback"
        )

        val allowedModes =
            intent.getIntegerArrayListExtra(
                DevicePolicyManager
                    .EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES
            )

        Log.d(
            TAG,
            "ProvisioningModeActivity: allowedModes=$allowedModes"
        )

        /*
         * Android tells the DPC which provisioning modes are allowed.
         *
         * Enhanced Protection requires a fully managed device, so request
         * FULLY_MANAGED_DEVICE only when Android has explicitly allowed it.
         */
        val fullyManagedAllowed =
            allowedModes?.contains(
                DevicePolicyManager
                    .PROVISIONING_MODE_FULLY_MANAGED_DEVICE
            ) == true

        if (!fullyManagedAllowed) {
            Log.w(
                TAG,
                "ProvisioningModeActivity: fully managed device " +
                    "mode is not allowed"
            )

            setResult(
                RESULT_CANCELED
            )

            finish()
            return
        }

        val resultIntent =
            Intent().apply {

                putExtra(
                    DevicePolicyManager
                        .EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager
                        .PROVISIONING_MODE_FULLY_MANAGED_DEVICE
                )

                /*
                 * AppLock does not need Android's generic education screens
                 * because the AppLock UI should explain Enhanced Protection
                 * before provisioning is initiated.
                 *
                 * We deliberately do not skip the OS screens here unless
                 * the provisioning flow explicitly requires it.
                 */
            }

        Log.d(
            TAG,
            "ProvisioningModeActivity: returning " +
                "PROVISIONING_MODE_FULLY_MANAGED_DEVICE"
        )

        setResult(
            RESULT_OK,
            resultIntent
        )

        finish()
    }
}