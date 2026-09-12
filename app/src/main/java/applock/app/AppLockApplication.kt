package applock.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityManager
import applock.app.data.AppLockRepository
import applock.app.domain.SessionRule
import applock.app.engine.LockEngine

class AppLockApplication : Application() {

    lateinit var repository: AppLockRepository
        private set

    lateinit var lockEngine: LockEngine
        private set

    override fun onCreate() {
        super.onCreate()

        repository = AppLockRepository(this)
        lockEngine = LockEngine(repository)

        repository.clearAllUnlocksIfNeededForColdStart()
        repository.refreshProtectionState()

        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(AccessibilityManager::class.java)
                ?.addAccessibilityServicesStateChangeListener {
                    repository.refreshProtectionState()
                }
        }

        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?
                ) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            if (
                                repository.getSessionRule() ==
                                SessionRule.SCREEN_OFF
                            ) {
                                repository.clearAllUnlocks()
                                lockEngine.resetTransitionState()
                            }

                            repository.refreshProtectionState()
                        }
                    }
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF)
        )
    }
}
