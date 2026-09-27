package applock.app.security

import android.app.Activity
import android.os.Bundle

/**
 * Minimal policy-compliance activity required by modern managed-device
 * provisioning flows.
 *
 * The actual AppLock security configuration remains in the normal app UI.
 */
class DeviceOwnerPolicyComplianceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setResult(RESULT_OK)
        finish()
    }
}
