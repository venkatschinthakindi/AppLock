package applock.app.ui.lock

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import applock.app.AppLockApplication
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class LockActivity : FragmentActivity() {

    companion object {
        const val EXTRA_PACKAGE_NAME = "protected_package"
    }

    private var packageNameTarget by mutableStateOf("")

    private var authenticationCompleted = false

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SECURE
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        packageNameTarget =
            intent
                .getStringExtra(EXTRA_PACKAGE_NAME)
                .orEmpty()

        val app =
            application as AppLockApplication

        if (!isValidTarget(app, packageNameTarget)) {
            finishAndRemoveTask()
            return
        }

        app.lockEngine.markAuthUiShown()

        /*
         * Back must never expose the protected application.
         */
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Intentionally blocked.
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
    }

    override fun onNewIntent(
        intent: Intent
    ) {
        super.onNewIntent(intent)

        setIntent(intent)

        val newTarget =
            intent
                .getStringExtra(EXTRA_PACKAGE_NAME)
                .orEmpty()

        val app =
            application as AppLockApplication

        if (!isValidTarget(app, newTarget)) {
            finishAndRemoveTask()
            return
        }

        /*
         * Do not switch target after authentication has already completed.
         * This avoids a race between accessibility events and UI callbacks.
         */
        if (!authenticationCompleted) {
            packageNameTarget = newTarget
            app.lockEngine.markAuthUiShown()
        }
    }

    private fun isValidTarget(
        app: AppLockApplication,
        targetPackage: String
    ): Boolean {

        if (targetPackage.isBlank()) {
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

    private fun completeAuthentication(
        targetPackage: String
    ) {
        if (authenticationCompleted) {
            return
        }

        val app =
            application as AppLockApplication

        /*
         * Fail closed if protection changed while the lock screen was visible.
         */
        if (!isValidTarget(app, targetPackage)) {
            app.lockEngine.resetTransitionState()
            finishAndRemoveTask()
            return
        }

        /*
         * Record successful authentication BEFORE launching the protected
         * application. This allows the engine to recognize the next
         * accessibility event as the authenticated return.
         */
        if (!app.lockEngine.unlock(targetPackage)) {
            return
        }

        val launchIntent =
            packageManager
                .getLaunchIntentForPackage(
                    targetPackage
                )

        if (launchIntent == null) {
            app.lockEngine.resetTransitionState()
            finishAndRemoveTask()
            return
        }

        authenticationCompleted = true

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
        )

        startActivity(launchIntent)

        finishAndRemoveTask()
    }
}