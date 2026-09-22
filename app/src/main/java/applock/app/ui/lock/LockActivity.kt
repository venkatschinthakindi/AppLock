package applock.app.ui.lock

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication
import applock.app.service.AppDetectionAccessibilityService

class LockActivity : FragmentActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "protected_package"
        const val EXTRA_REQUEST_ID = "protected_request_id"
    }

    private var packageNameTarget by mutableStateOf("")
    private var requestId = 0L

    private var authenticationCompleted = false

    /**
     * True while Android's system BiometricPrompt owns the foreground.
     * Showing BiometricPrompt can move this Activity through onStop/onDestroy
     * on some devices. That lifecycle transition is NOT an authentication
     * cancellation and must not invalidate the active lock request.
     */
    private var biometricPromptActive = false

    /**
     * READY is one-shot for this Activity/request.
     */
    private var lockUiReadyReported = false

    /**
     * Prevent duplicate onStop/onDestroy dismissal callbacks.
     */
    private var dismissalReported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        overridePendingTransition(0, 0)

        window.setWindowAnimations(0)

        /*
         * Keep the Activity itself opaque.
         */
        window.setBackgroundDrawable(
            ColorDrawable(Color.BLACK)
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SECURE
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        if (!bindRequest(intent)) {
            return
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    /*
                     * Back is an explicit user cancellation.
                     *
                     * Never use moveTaskToBack(), because that can expose
                     * the protected task.
                     */
                    cancelAuthenticationAndGoHome()
                }
            }
        )

        setContent {
            LockScreen(
                packageName = packageNameTarget,
                onSuccess = {
                    completeAuthentication(
                        packageNameTarget
                    )
                }
            )
        }

        installLockUiReadyHandshake()
    }

    // ---------------------------------------------------------------------
    // READY
    // ---------------------------------------------------------------------

    /**
     * READY means:
     *
     * - exact request is still active
     * - Activity is alive
     * - Activity currently owns window focus
     *
     * A previously posted READY callback cannot succeed after focus has
     * already been lost.
     */
    private fun reportLockUiReady(
        source: String
    ) {
        if (lockUiReadyReported) {
            return
        }

        if (
            packageNameTarget.isBlank() ||
            requestId == 0L
        ) {
            return
        }

        if (isFinishing || isDestroyed) {
            return
        }

        /*
         * CRITICAL RACE GUARD.
         *
         * onWindowFocusChanged(true) may schedule a callback, followed by
         * focus loss before that callback executes.
         */
        if (!window.decorView.hasWindowFocus()) {
            android.util.Log.d(
                "AppLockDiag",
                "LockActivity READY rejected: " +
                    "window lost focus before callback " +
                    "source=$source pkg=$packageNameTarget " +
                    "requestId=$requestId"
            )
            return
        }

        val app =
            application as AppLockApplication

        if (
            !app.lockEngine.isRequestActive(
                packageNameTarget,
                requestId
            )
        ) {
            return
        }

        lockUiReadyReported = true

        android.util.Log.d(
            "AppLockDiag",
            "LockActivity READY source=$source " +
                "pkg=$packageNameTarget " +
                "requestId=$requestId"
        )

        AppDetectionAccessibilityService
            .notifyLockActivityReady(
                packageNameTarget,
                requestId
            )
    }

    override fun onWindowFocusChanged(
        hasFocus: Boolean
    ) {
        super.onWindowFocusChanged(hasFocus)

        if (!hasFocus) {
            return
        }

        /*
         * Post one frame so the Activity has completed the focus transition.
         *
         * reportLockUiReady() rechecks hasWindowFocus(), so focus loss
         * between these two callbacks is safe.
         */
        window.decorView.postOnAnimation {
            reportLockUiReady("windowFocus")
        }
    }

    private fun installLockUiReadyHandshake() {
        /*
         * There is intentionally no pre-draw -> READY transition.
         *
         * Drawing does not prove that this Activity is the focused/top
         * security surface.
         *
         * Window focus is the minimum condition accepted here.
         */
    }

    // ---------------------------------------------------------------------
    // REQUEST BINDING
    // ---------------------------------------------------------------------

    override fun onNewIntent(
        intent: Intent
    ) {
        super.onNewIntent(intent)

        setIntent(intent)

        if (authenticationCompleted) {
            return
        }

        /*
         * Do not silently adopt a different transaction into an existing
         * Activity instance.
         *
         * The service should normally launch a fresh LockActivity.
         */
        val target =
            intent.getStringExtra(
                EXTRA_PACKAGE_NAME
            ).orEmpty()

        val id =
            intent.getLongExtra(
                EXTRA_REQUEST_ID,
                0L
            )

        if (
            target != packageNameTarget ||
            id != requestId
        ) {
            android.util.Log.w(
                "AppLockDiag",
                "LockActivity rejecting onNewIntent " +
                    "because request changed. " +
                    "old=$packageNameTarget/$requestId " +
                    "new=$target/$id"
            )

            finishAndRemoveTask()
            return
        }

        /*
         * Same exact request only.
         */
        lockUiReadyReported = false
        dismissalReported = false

        if (!bindRequest(intent)) {
            return
        }

        installLockUiReadyHandshake()
    }

    private fun bindRequest(
        source: Intent?
    ): Boolean {

        val app =
            application as AppLockApplication

        val target =
            source
                ?.getStringExtra(
                    EXTRA_PACKAGE_NAME
                )
                .orEmpty()

        val id =
            source?.getLongExtra(
                EXTRA_REQUEST_ID,
                0L
            ) ?: 0L

        if (
            !isValidTarget(app, target) ||
            !app.lockEngine.isRequestActive(
                target,
                id
            )
        ) {
            finishAndRemoveTask()
            return false
        }

        packageNameTarget = target
        requestId = id

        app.lockEngine.markAuthUiShown()

        AppDetectionAccessibilityService
            .notifyLockActivityShown(
                target,
                id
            )

        return true
    }

    // ---------------------------------------------------------------------
    // RESUME
    // ---------------------------------------------------------------------

    override fun onResume() {
        super.onResume()

        if (packageNameTarget.isBlank()) {
            return
        }

        val app =
            application as AppLockApplication

        /*
         * A stale Activity can never revive itself after its request has
         * already completed/cancelled.
         */
        if (
            !isValidTarget(
                app,
                packageNameTarget
            ) ||
            !app.lockEngine.isRequestActive(
                packageNameTarget,
                requestId
            )
        ) {
            finishAndRemoveTask()
            return
        }

        AppDetectionAccessibilityService
            .notifyLockActivityShown(
                packageNameTarget,
                requestId
            )
    }

    // ---------------------------------------------------------------------
    // EXPLICIT USER CANCELLATION
    // ---------------------------------------------------------------------

    /**
     * Explicit user cancellation is fundamentally different from lifecycle
     * disappearance.
     *
     * This callback is only used by a deliberate user action such as:
     * - Back
     * - Skip
     * - Close
     *
     * The service will reconcile the actual foreground before removing the
     * privacy barrier.
     */
    private fun cancelAuthenticationAndGoHome() {

        if (authenticationCompleted) {
            return
        }

        if (
            packageNameTarget.isBlank() ||
            requestId == 0L
        ) {
            goHomeAndFinish()
            return
        }

        AppDetectionAccessibilityService
            .notifyLockActivityUserCancelled(
                packageNameTarget,
                requestId
            )

        /*
         * The service owns the security transaction.
         * Activity only finishes itself.
         */
        goHomeAndFinish()
    }

    // ---------------------------------------------------------------------
    // BIOMETRIC PROMPT LIFECYCLE
    // ---------------------------------------------------------------------

    fun setBiometricPromptActive(active: Boolean) {
        biometricPromptActive = active

        val state =
            if (active) {
                "STARTED"
            } else {
                "IDLE"
            }

        AppDetectionAccessibilityService.notifyBiometricPromptState(
            packageNameTarget,
            requestId,
            state
        )

        android.util.Log.d(
            "AppLockDiag",
            "LockActivity biometricPromptActive=$active pkg=$packageNameTarget requestId=$requestId state=$state"
        )
    }

    /**
     * Request-bound biometric lifecycle notification. The service owns the
     * foreground authentication transaction, so it must hear about success
     * before the Activity releases the biometric guard.
     */
    fun notifyBiometricPromptState(state: String) {
        AppDetectionAccessibilityService.notifyBiometricPromptState(
            packageNameTarget,
            requestId,
            state
        )

        android.util.Log.d(
            "AppLockDiag",
            "LockActivity biometricPrompt state=$state pkg=$packageNameTarget requestId=$requestId"
        )
    }

    /**
     * BiometricPrompt can outlive/destroy this Activity on some OEM builds.
     * The Accessibility service is the transaction owner, so biometric success
     * uses the service-owned launch bridge instead of depending on Activity
     * liveness.
     */
    fun completeBiometricAuthenticationHandoff(): Boolean {
        return AppDetectionAccessibilityService
            .completeBiometricAuthenticationHandoff(
                packageNameTarget,
                requestId
            )
    }

    /**
     * Last-resort compatibility path for devices where the service instance
     * is temporarily unavailable but the Activity is still alive.
     */
    fun completeAuthenticationFromBiometricFallback(
        targetPackage: String
    ): Boolean {
        if (isFinishing || isDestroyed) {
            return false
        }

        completeAuthentication(targetPackage)
        return authenticationCompleted
    }

    // ---------------------------------------------------------------------
    // LIFECYCLE DISMISSAL
    // ---------------------------------------------------------------------

    override fun onStop() {
        super.onStop()

        // BiometricPrompt temporarily owns the foreground. Do not treat its
        // lifecycle transition as dismissal of the protected-app lock UI.
        if (biometricPromptActive) {
            android.util.Log.d(
                "AppLockDiag",
                "LockActivity onStop ignored while BiometricPrompt active " +
                    "pkg=$packageNameTarget requestId=$requestId"
            )
            return
        }

        reportLockUiDismissedOnce()
    }

    override fun onDestroy() {

        /*
         * BiometricPrompt may cause a transient lifecycle transition on some
         * Android versions. If the prompt is active, the authentication
         * transaction is still alive and must not be dismissed here.
         */
        if (biometricPromptActive) {
            android.util.Log.d(
                "AppLockDiag",
                "LockActivity onDestroy ignored while BiometricPrompt active " +
                    "pkg=$packageNameTarget requestId=$requestId"
            )
            super.onDestroy()
            return
        }

        /*
         * This is only an unexpected/lifecycle disappearance.
         *
         * Do NOT cancel the request here.
         */
        reportLockUiDismissedOnce()

        super.onDestroy()
    }

    private fun reportLockUiDismissedOnce() {

        if (authenticationCompleted) {
            return
        }

        if (dismissalReported) {
            return
        }

        if (
            packageNameTarget.isBlank() ||
            requestId == 0L
        ) {
            return
        }

        dismissalReported = true

        AppDetectionAccessibilityService
            .notifyLockActivityDismissed(
                packageNameTarget,
                requestId
            )
    }

    // ---------------------------------------------------------------------
    // AUTHENTICATION SUCCESS
    // ---------------------------------------------------------------------

    private fun completeAuthentication(
        targetPackage: String
    ) {

        if (authenticationCompleted) {
            return
        }

        val app =
            application as AppLockApplication

        if (isFinishing || isDestroyed) {
            return
        }

        /*
         * IMPORTANT:
         *
         * PIN/Pattern/Biometric authentication completes the LockEngine
         * transaction first. LockEngine intentionally invalidates the active
         * request at that point, while preserving the exact one-shot
         * launch authorization.
         *
         * Therefore the post-authentication hand-off must NOT require
         * isRequestActive() here. The authoritative proof of successful
         * authentication is the exact package + requestId launch
         * authorization created by LockEngine.
         */
        if (
            !app.lockEngine.hasLaunchAuthorization(
                targetPackage,
                requestId
            )
        ) {
            android.util.Log.w(
                "AppLockDiag",
                "Launch rejected: missing exact post-auth authorization " +
                    "pkg=$targetPackage requestId=$requestId"
            )

            finishAndRemoveTask()
            return
        }

        if (
            !isValidTarget(
                app,
                targetPackage
            )
        ) {
            app.lockEngine.cancelRequest(
                targetPackage,
                requestId
            )

            finishAndRemoveTask()
            return
        }

        /*
         * The PIN/biometric operation must already have authenticated this
         * exact request.
         *
         * LockScreen should only invoke this callback after the corresponding
         * LockEngine authentication method returned SUCCESS.
         */
        if (
            !app.lockEngine.hasLaunchAuthorization(
                targetPackage,
                requestId
            )
        ) {
            android.util.Log.w(
                "AppLockDiag",
                "Launch rejected: no exact launch authorization " +
                    "pkg=$targetPackage requestId=$requestId"
            )

            finishAndRemoveTask()
            return
        }

        val launchIntent =
            packageManager.getLaunchIntentForPackage(
                targetPackage
            )

        if (launchIntent == null) {
            app.lockEngine.cancelRequest(
                targetPackage,
                requestId
            )

            finishAndRemoveTask()
            return
        }

        /*
         * Consume exact one-shot authorization BEFORE launching.
         *
         * If a stale callback runs again, the authorization is already gone.
         */
        if (
            !app.lockEngine.consumeLaunchAuthorization(
                targetPackage,
                requestId
            )
        ) {
            android.util.Log.w(
                "AppLockDiag",
                "Launch rejected: authorization already consumed " +
                    "pkg=$targetPackage requestId=$requestId"
            )

            finishAndRemoveTask()
            return
        }

        authenticationCompleted = true

        AppDetectionAccessibilityService
            .notifyAuthenticationSucceeded(
                targetPackage,
                requestId
            )

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        startActivity(launchIntent)

        finishAndRemoveTask()
    }

    // ---------------------------------------------------------------------
    // NAVIGATION
    // ---------------------------------------------------------------------

    private fun goHomeAndFinish() {

        runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)

                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        }

        finishAndRemoveTask()
    }

    // ---------------------------------------------------------------------
    // VALIDATION
    // ---------------------------------------------------------------------

    private fun isValidTarget(
        app: AppLockApplication,
        targetPackage: String
    ): Boolean {

        if (targetPackage.isBlank()) {
            return false
        }

        if (targetPackage == packageName) {
            return false
        }

        if (!app.repository.isProtected(targetPackage)) {
            return false
        }

        return packageManager
            .getLaunchIntentForPackage(
                targetPackage
            ) != null
    }
}