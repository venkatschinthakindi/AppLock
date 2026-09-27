package applock.app.ui

import android.content.Context

object ProtectionModeState {
    enum class Mode { STANDARD, ENHANCED }
    private const val PREFS_NAME = "app_lock_protection_mode"
    private const val KEY_MODE = "mode"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSelected(context: Context): Boolean = preferences(context).contains(KEY_MODE)

    fun get(context: Context): Mode =
        when (preferences(context).getString(KEY_MODE, Mode.STANDARD.name)) {
            Mode.ENHANCED.name -> Mode.ENHANCED
            else -> Mode.STANDARD
        }

    fun set(context: Context, mode: Mode) {
        preferences(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun isEnhanced(context: Context): Boolean = get(context) == Mode.ENHANCED
    fun isStandard(context: Context): Boolean = get(context) == Mode.STANDARD

    fun reset(context: Context) {
        preferences(context).edit().remove(KEY_MODE).apply()
    }
}
