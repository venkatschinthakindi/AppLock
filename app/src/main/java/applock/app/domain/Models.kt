package applock.app.domain

enum class AuthMethod { PIN, PATTERN, BIOMETRIC }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class SessionRule { IMMEDIATELY, AFTER_LEAVING, SCREEN_OFF, MINUTES_1, MINUTES_5, MINUTES_15, MINUTES_30 }
enum class HealthState { GREEN, YELLOW, RED }
enum class AnimationStyle { COSMIC_ORB, LIQUID_FLOW, CRYSTAL_UNLOCK }

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Long = 0xFF6C63FF,
    val cornerRadius: Float = 20f,
    val animationScale: Float = 1f,
    val animationStyle: AnimationStyle = AnimationStyle.COSMIC_ORB,
    val reducedMotion: Boolean = false
)

data class ProtectedApp(
    val packageName: String,
    val label: String,
    val protected: Boolean
)

data class HealthCheck(
    val key: String,
    val title: String,
    val state: HealthState,
    val detail: String
)
