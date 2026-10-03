package applock.app.security

import android.app.Activity
import android.os.Bundle

/** Android 12+ managed-device policy-compliance callback. */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}
