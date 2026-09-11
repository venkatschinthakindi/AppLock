package applock.app.compat

import android.content.Context
import android.os.Build
import android.os.PowerManager

object DeviceCompatibility {
    fun manufacturer(): String = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    fun batteryGuidance(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java)
        return if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) "No app-specific battery optimization is currently reported." else "Battery optimization may affect background detection on some manufacturers. Use only the device's normal battery/background settings; App Lock does not bypass system restrictions."
    }
}
