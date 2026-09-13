package applock.app

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import applock.app.security.AntiTamperManager
import applock.app.ui.AppLockRoot
import applock.app.ui.screens.DeviceAdminExplanationScreen
import applock.app.ui.theme.AppLockTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SHOW_DEVICE_ADMIN_EXPLANATION =
            "show_device_admin_explanation"
    }

    private var showDeviceAdminExplanation by mutableStateOf(false)

    /*
     * True only while the Android Device Admin screen has been launched
     * from our explanation screen.
     */
    private var deviceAdminRequestLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as AppLockApplication

        if (Build.VERSION.SDK_INT >= 35) {
            val startInfo = getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessStartReasons(1)
                ?.firstOrNull()

            if (startInfo?.wasForceStopped() == true) {
                app.repository.markForceStopRecovery()
                app.lockEngine.resetTransitionState()
                app.repository.refreshProtectionState()
            }
        }

        app.repository.refreshProtectionState()

        /*
         * There are two ways to enter the explanation:
         *
         * 1. Automatic first-run security flow.
         * 2. Explicit recovery action from Home.
         */
        showDeviceAdminExplanation =
            intent.getBooleanExtra(
                EXTRA_SHOW_DEVICE_ADMIN_EXPLANATION,
                false
            ) || AntiTamperManager.shouldShowDeviceAdminExplanation(this)

        setContent {
            val theme by app.repository.theme.collectAsState()

            AppLockTheme(theme) {
                if (showDeviceAdminExplanation) {
                    DeviceAdminExplanationScreen(
                        onEnableProtection = {
                            deviceAdminRequestLaunched = true

                            /*
                             * Mark the explanation as handled before
                             * entering Android's system UI.
                             */
                            AntiTamperManager.markDeviceAdminExplanationShown(
                                this
                            )

                            val launched =
                                AntiTamperManager.requestDeviceAdmin(this)

                            /*
                             * If Android could not launch the Device Admin
                             * screen, return to normal AppLock UI.
                             */
                            if (!launched) {
                                deviceAdminRequestLaunched = false
                                showDeviceAdminExplanation = false
                            }
                        },
                        onNotNow = {
                            AntiTamperManager.markDeviceAdminExplanationShown(
                                this
                            )

                            showDeviceAdminExplanation = false
                        }
                    )
                } else {
                    AppLockRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        setIntent(intent)

        if (
            intent.getBooleanExtra(
                EXTRA_SHOW_DEVICE_ADMIN_EXPLANATION,
                false
            )
        ) {
            val app = application as AppLockApplication

            app.repository.refreshProtectionState()

            showDeviceAdminExplanation =
                !AntiTamperManager.isDeviceAdminActive(this)
        }
    }

    override fun onResume() {
        super.onResume()

        val app = application as AppLockApplication

        app.repository.refreshProtectionState()

        /*
         * If Android's Device Admin screen was launched, this Activity
         * resumes when the user returns from it.
         *
         * Always check the actual OS state.
         */
        if (deviceAdminRequestLaunched) {
            deviceAdminRequestLaunched = false

            val enabled =
                AntiTamperManager.isDeviceAdminActive(this)

            /*
             * Whether the user enabled or cancelled Device Admin,
             * return to normal AppLock UI.
             *
             * ProtectionHealthScreen remains responsible for showing
             * the actual current protection state.
             */
            showDeviceAdminExplanation = false

            if (enabled) {
                app.repository.refreshProtectionState()
            }
        }
    }
}