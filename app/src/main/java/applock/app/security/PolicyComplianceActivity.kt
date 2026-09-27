package applock.app.security

import android.app.Activity
import android.os.Bundle

/**
 * Android 12+ DPC policy-compliance endpoint.
 *
 * AppLock currently has no additional enterprise policy that needs
 * to be configured during provisioning, so provisioning can continue.
 */
class PolicyComplianceActivity : Activity() {

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        setResult(
            RESULT_OK
        )

        finish()
    }
}