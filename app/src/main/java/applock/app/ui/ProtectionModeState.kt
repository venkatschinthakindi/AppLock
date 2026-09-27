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

    /**
     * True only when the user has made an explicit, persisted choice of
     * Standard Protection — as opposed to simply never having selected a
     * mode (isSelected() == false), where [get] still defaults to
     * STANDARD. Callers that want to honor a deliberate opt-out of
     * Device Admin (e.g. suppressing an automatic Device Admin prompt)
     * must use this, not `isStandard(context)` alone, or they will also
     * suppress the prompt for users who were never asked at all —
     * including everyone who used AppLock before Protection Mode existed.
     */
    fun isExplicitStandardChoice(context: Context): Boolean =
        isSelected(context) && isStandard(context)

    fun reset(context: Context) {
        preferences(context).edit().remove(KEY_MODE).apply()
    }
}
