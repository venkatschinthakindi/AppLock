package applock.app.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle

/**
 * Android 12+ managed-device provisioning mode callback.
 *
 * The system provisioning controller launches this activity while enrolling
 * AppLock as the Device Policy Controller. AppLock requests a fully managed
 * device because Enhanced Protection intentionally uses device-owner policy.
 */
class DeviceOwnerProvisioningModeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action != DevicePolicyManager.ACTION_GET_PROVISIONING_MODE) {
            finish()
            return
        }

        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE
                )
            }
        )

        finish()
    }
}
