package applock.app.ui.lock

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication
import applock.app.security.AntiTamperManager

class SecurityGateActivity : FragmentActivity() {
    companion object {
        const val EXTRA_MANAGEMENT_PACKAGE = "management_package"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        val app = application as AppLockApplication
        if (!app.repository.authenticationConfigured()) {
            finishAndRemoveTask()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        val managementPackage = intent.getStringExtra(EXTRA_MANAGEMENT_PACKAGE).orEmpty()

        setContent {
            SecurityGateScreen(
                onAuthenticated = {
                    if (managementPackage.isNotBlank()) {
                        AntiTamperManager.grantManagementAccess(managementPackage)
                    }
                    finishAndRemoveTask()
                }
            )
        }
    }
}
