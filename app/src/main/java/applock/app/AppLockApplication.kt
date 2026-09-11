package applock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.app.Application
import applock.app.data.AppLockRepository
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

        // Screen-off ends unlock sessions for the SCREEN_OFF rule without polling.
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF && repository.getSessionRule() == applock.app.domain.SessionRule.SCREEN_OFF) {
                    repository.clearAllUnlocks()
                    lockEngine.reset()
                }
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF), if (Build.VERSION.SDK_INT >= 33) RECEIVER_NOT_EXPORTED else 0)
    }
}
