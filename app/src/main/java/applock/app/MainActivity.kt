package applock.app

import android.app.ActivityManager
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
import applock.app.service.AppDetectionAccessibilityService
import applock.app.ui.AppLockRoot
import applock.app.ui.screens.DeviceAdminExplanationScreen
import applock.app.ui.theme.AppLockTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_SHOW_DEVICE_ADMIN_EXPLANATION =
            "show_device_admin_explanation"
    }

    private var showDeviceAdminExplanation by mutableStateOf(false)
    private var deviceAdminRequestLaunched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as AppLockApplication

        // Opening the real AppLock UI is an explicit security boundary.
        // Any stale protected-app lock request must be cancelled immediately.
        app.lockEngine.onAppLockVisible()
        AppDetectionAccessibilityService.notifyAppLockMainUiShown()
        AppDetectionAccessibilityService.releaseForegroundBarrier()

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
                            AntiTamperManager.markDeviceAdminExplanationShown(this)
                            val launched = AntiTamperManager.requestDeviceAdmin(this)
                            if (!launched) {
                                deviceAdminRequestLaunched = false
                                showDeviceAdminExplanation = false
                            }
                        },
                        onNotNow = {
                            AntiTamperManager.markDeviceAdminExplanationShown(this)
                            showDeviceAdminExplanation = false
                        }
                    )
                } else {
                    AppLockRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val app = application as AppLockApplication
        app.lockEngine.onAppLockVisible()
        AppDetectionAccessibilityService.notifyAppLockMainUiShown()
        AppDetectionAccessibilityService.releaseForegroundBarrier()

        if (intent.getBooleanExtra(EXTRA_SHOW_DEVICE_ADMIN_EXPLANATION, false)) {
            app.repository.refreshProtectionState()
            showDeviceAdminExplanation =
                !AntiTamperManager.isDeviceAdminActive(this)
        }
    }

    override fun onResume() {
        super.onResume()

        val app = application as AppLockApplication

        // onResume is the final boundary: even if AccessibilityService missed
        // the MainActivity window event, returning to AppLock cancels stale
        // protected-app authentication and removes any transition barrier.
        app.lockEngine.onAppLockVisible()
        AppDetectionAccessibilityService.notifyAppLockMainUiShown()
        AppDetectionAccessibilityService.releaseForegroundBarrier()
        app.repository.refreshProtectionState()

        if (deviceAdminRequestLaunched) {
            deviceAdminRequestLaunched = false
            val enabled = AntiTamperManager.isDeviceAdminActive(this)
            showDeviceAdminExplanation = false
            if (enabled) {
                app.repository.refreshProtectionState()
            }
        }
    }
}
