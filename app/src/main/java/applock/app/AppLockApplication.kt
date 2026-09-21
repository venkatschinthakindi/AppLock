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
import applock.app.engine.LockEngine
import applock.app.security.AntiTamperManager
import applock.app.service.AppDetectionAccessibilityService
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
                            /*
                             * Screen off is the hardest boundary there is.
                             *
                             * Foreground authorization is ALWAYS destroyed,
                             * regardless of the configured session rule.
                             * Otherwise a protected app left in the foreground
                             * stays authorized across a device lock and is
                             * re-entered without any challenge.
                             *
                             * Persisted unlock timestamps follow the user's
                             * configured rule.
                             */
                            lockEngine.onScreenOff()
                            repository.clearUnlocksForScreenOff()
                            AppDetectionAccessibilityService.notifyScreenOff()
                            repository.refreshProtectionState()
                        }

                        Intent.ACTION_SCREEN_ON,
                        Intent.ACTION_USER_PRESENT -> {
                            repository.refreshProtectionState()
                            AppDetectionAccessibilityService.notifyScreenOn()
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
                addAction(Intent.ACTION_USER_PRESENT)
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