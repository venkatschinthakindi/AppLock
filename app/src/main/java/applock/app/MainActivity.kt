package applock.app

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import applock.app.security.AntiTamperManager
import applock.app.ui.AppLockRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AppLockApplication
        if (Build.VERSION.SDK_INT >= 35) {
            val startInfo = getSystemService(ActivityManager::class.java)
                ?.getHistoricalProcessStartReasons(1)
                ?.firstOrNull()
            if (startInfo?.wasForceStopped() == true) {
                app.repository.markForceStopRecovery()
                app.lockEngine.resetTransitionState()
                app.repository.refreshProtectionState()
            }
        }
        app.repository.refreshProtectionState()
        AntiTamperManager.maybeRequestDeviceAdmin(this)
        setContent { AppLockRoot() }
    }

    override fun onResume() {
        super.onResume()
        val app = application as AppLockApplication
        app.repository.refreshProtectionState()
        AntiTamperManager.maybeRequestDeviceAdmin(this)
    }
}
