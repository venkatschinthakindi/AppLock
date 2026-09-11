package applock.app

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
    }
}
