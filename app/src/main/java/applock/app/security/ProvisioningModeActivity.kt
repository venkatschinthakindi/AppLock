package applock.app.security

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Android 12+ managed-device provisioning callback.
 *
 * AppLock requires a fully managed device because Enhanced Protection
 * depends on Device Owner authority.
 */
class ProvisioningModeActivity : Activity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            setResult(
                RESULT_CANCELED
            )

            finish()
            return
        }

        val result =
            Intent().apply {

                putExtra(
                    DevicePolicyManager
                        .EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager
                        .PROVISIONING_MODE_FULLY_MANAGED_DEVICE
                )
            }

        setResult(
            RESULT_OK,
            result
        )

        finish()
    }
}