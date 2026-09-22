package applock.app

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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

class MainActivity : FragmentActivity() {

    companion object {
        const val EXTRA_SHOW_DEVICE_ADMIN_EXPLANATION =
            "show_device_admin_explanation"

        const val EXTRA_OPEN_PROTECTED_APPS =
            "open_protected_apps"
    }

    private var showDeviceAdminExplanation by mutableStateOf(false)
    private var deviceAdminRequestLaunched = false
    private var openProtectedApps by mutableStateOf(false)

    /**
     * Launches the real Android biometric prompt from the Activity host.
     * Compose screens must not try to construct BiometricPrompt themselves.
     */
    fun launchDeviceBiometric(
        title: String,
        subtitle: String,
        negativeButtonText: String = "Cancel",
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val availability = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        )

        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            val message = when (availability) {
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                    "This device does not have biometric hardware."
                BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                    "Device biometric hardware is temporarily unavailable."
                BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                    "No fingerprint or face is enrolled. Add one in Android Settings, then try again."
                BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                    "A device security update is required before biometric authentication can be used."
                else ->
                    "Device biometric authentication is not available right now."
            }
            onError(message)
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence
                ) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
                                "Device biometric verification was cancelled. No authentication setting was changed."
                            else ->
                                "Device biometric verification was not completed. No authentication setting was changed."
                        }
                    )
                }
            }
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText(negativeButtonText)
                .setConfirmationRequired(false)
                .build()
        )
    }

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
        openProtectedApps =
            intent.getBooleanExtra(
                EXTRA_OPEN_PROTECTED_APPS,
                false
            )

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
                    AppLockRoot(
                        openProtectedApps = openProtectedApps
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        openProtectedApps =
            intent.getBooleanExtra(
                EXTRA_OPEN_PROTECTED_APPS,
                false
            )

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
