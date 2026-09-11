package applock.app.engine

import android.os.SystemClock
import applock.app.data.AppLockRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local deterministic gate. It never performs network/ad/billing work. */
class LockEngine(private val repository: AppLockRepository) {
    enum class State { IDLE, PROTECTED_APP_DETECTED, CHECKING_STATE, AUTHENTICATION_REQUIRED, SHOWING_AUTH, UNLOCKED, LIMITED_PROTECTION }
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private var lastLaunchPackage: String? = null
    private var lastLaunchElapsed = 0L

    @Synchronized fun onPackageVisible(packageName: String): Boolean {
        if (packageName == lastLaunchPackage && SystemClock.elapsedRealtime() - lastLaunchElapsed < 250L) return false
        lastLaunchPackage = packageName
        lastLaunchElapsed = SystemClock.elapsedRealtime()
        if (!repository.isProtected(packageName)) { _state.value = State.IDLE; return false }
        _state.value = State.PROTECTED_APP_DETECTED
        _state.value = State.CHECKING_STATE
        val required = repository.shouldRequireAuth(packageName)
        _state.value = if (required) State.AUTHENTICATION_REQUIRED else State.UNLOCKED
        return required
    }

    fun markAuthUiShown() { _state.value = State.SHOWING_AUTH }
    fun unlock(packageName: String) { repository.markUnlocked(packageName); _state.value = State.UNLOCKED }
    fun reset() { _state.value = State.IDLE; lastLaunchPackage = null }
}
