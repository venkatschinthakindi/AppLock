package applock.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityManager
import com.google.android.gms.ads.MobileAds
import applock.app.data.AppLockRepository
import applock.app.domain.SessionRule
import applock.app.engine.LockEngine
import applock.app.security.AntiTamperManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class AppLockApplication : Application() {

    lateinit var repository: AppLockRepository
        private set

    lateinit var lockEngine: LockEngine
        private set

    private val adsInitializationStarted = AtomicBoolean(false)

    @Volatile
    private var adsInitialized = false

    private val adsInitializationCallbacks =
        CopyOnWriteArrayList<() -> Unit>()

    override fun onCreate() {
        super.onCreate()

        repository = AppLockRepository(this)
        lockEngine = LockEngine(repository)

        repository.clearAllUnlocksIfNeededForColdStart()
        repository.refreshProtectionState()
        lockEngine.resetTransitionState()

        AntiTamperManager.enforceStrongProtection(
            this,
            repository.protectedPackages()
        )

        if (Build.VERSION.SDK_INT >= 33) {
            getSystemService(
                AccessibilityManager::class.java
            )?.addAccessibilityServicesStateChangeListener {
                repository.refreshProtectionState()

                AntiTamperManager.enforceStrongProtection(
                    this,
                    repository.protectedPackages()
                )
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

                        Intent.ACTION_SCREEN_ON -> {
                            repository.refreshProtectionState()
                        }
                    }

                    AntiTamperManager.enforceStrongProtection(
                        this@AppLockApplication,
                        repository.protectedPackages()
                    )
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            if (Build.VERSION.SDK_INT >= 33) {
                RECEIVER_NOT_EXPORTED
            } else {
                0
            }
        )
    }

    /**
     * Initializes Google Mobile Ads only after UMP has confirmed
     * that ads can be requested.
     *
     * Authentication and the lock engine never depend on this method.
     */
    fun initializeAdsIfAllowed(
        onInitialized: () -> Unit = {}
    ) {
        if (adsInitialized) {
            onInitialized()
            return
        }

        adsInitializationCallbacks += onInitialized

        if (!adsInitializationStarted.compareAndSet(false, true)) {
            return
        }

        MobileAds.initialize(this) {
            adsInitialized = true

            val callbacks =
                adsInitializationCallbacks.toList()

            adsInitializationCallbacks.clear()

            callbacks.forEach { callback ->
                runCatching {
                    callback()
                }
            }
        }
    }

    fun isAdsInitialized(): Boolean = adsInitialized
}