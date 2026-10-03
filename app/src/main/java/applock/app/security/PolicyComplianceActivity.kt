package applock.app.security

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Android 12+ managed-device policy-compliance callback.
 *
 * Android invokes this activity during managed provisioning so the DPC
 * can present/check its policy requirements before provisioning completes.
 */
class PolicyComplianceActivity : Activity() {

    companion object {
        private const val TAG = "AppLockDiag"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(
            TAG,
            "PolicyComplianceActivity: provisioning compliance callback received"
        )

        /*
         * AppLock currently has no additional policy-compliance screen
         * required to complete Device Owner provisioning.
         *
         * Returning RESULT_OK tells Android that the DPC's compliance
         * step completed successfully.
         */
        setResult(
            RESULT_OK
        )

        finish()
    }
}