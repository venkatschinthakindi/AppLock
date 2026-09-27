package applock.app.ui.enhanced

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import applock.app.AppLockApplication
import applock.app.security.EnhancedProtectionManager
import applock.app.security.ProtectionPolicy
import applock.app.ui.lock.LockActivity

/**
 * OS-gated Home surface used by Enhanced Protection.
 *
 * When AppLock owns ROLE_HOME, protected packages remain suspended. Tapping a
 * protected package here creates the same LockEngine transaction used by the
 * existing Accessibility path. Only after exact authentication authorization
 * is granted does LockActivity unsuspend and launch the target.
 */
class EnhancedHomeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        render()
    }

    override fun onResume() {
        super.onResume()

        val app = application as AppLockApplication

        if (ProtectionPolicy.isEnhancedEnforced(this)) {
            EnhancedProtectionManager.reapplyAfterReturnToGate(
                this,
                app.repository.protectedPackages()
            )
        }

        render()
    }

    private fun render() {
        val app = application as AppLockApplication
        val packages = app.repository.protectedPackages()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply {
            text = "AppLock"
            textSize = 28f
            gravity = Gravity.CENTER
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val subtitle = TextView(this).apply {
            text =
                if (ProtectionPolicy.isEnhancedEnforced(this@EnhancedHomeActivity)) {
                    "Enhanced Protection is active"
                } else {
                    "Enhanced Protection requires Device Owner + Home role"
                }
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 32)
        }

        root.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        for (pkg in packages.sorted()) {
            val label =
                runCatching {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(pkg, 0)
                    ).toString()
                }.getOrDefault(pkg)

            val button = Button(this).apply {
                text = label
                setOnClickListener {
                    launchThroughLockEngine(pkg)
                }
            }

            root.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 12
                }
            )
        }

        setContentView(root)
    }

    private fun launchThroughLockEngine(packageName: String) {
        val app = application as AppLockApplication

        if (!app.repository.isProtected(packageName)) {
            return
        }

        val decision =
            app.lockEngine.onForegroundApp(packageName)

        if (!decision.requireAuth) {
            if (
                !EnhancedProtectionManager.prepareAuthorizedLaunch(
                    this,
                    packageName
                )
            ) {
                render()
                return
            }

            packageManager
                .getLaunchIntentForPackage(packageName)
                ?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }

            return
        }

        startActivity(
            Intent(this, LockActivity::class.java).apply {
                putExtra(
                    LockActivity.EXTRA_PACKAGE_NAME,
                    decision.packageName
                )
                putExtra(
                    LockActivity.EXTRA_REQUEST_ID,
                    decision.requestId
                )
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
        )
    }
}
