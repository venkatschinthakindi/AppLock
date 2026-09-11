package applock.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityManager
import android.os.Build
import applock.app.data.AppLockRepository
import applock.app.engine.LockEngine
import applock.app.domain.SessionRule

class AppLockApplication : Application() {
    lateinit var repository: AppLockRepository
        private set
    lateinit var lockEngine: LockEngine
        private set

    override fun onCreate() {
        super.onCreate()
        repository = AppLockRepository(this)
        lockEngine = LockEngine(repository)

        // Runtime state is always derived again when the app process is created.
        repository.clearAllUnlocksIfNeededForColdStart()
        repository.refreshProtectionState()

        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(AccessibilityManager::class.java)?.addAccessibilityServicesStateChangeListener {
                repository.refreshProtectionState()
            }
        }

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        if (repository.getSessionRule() == SessionRule.SCREEN_OFF) {
                            repository.clearAllUnlocks()
                            lockEngine.reset()
                        }
                        repository.refreshProtectionState()
                    }
                    Intent.ACTION_SCREEN_ON -> repository.refreshProtectionState()
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }, if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0)
    }
}
