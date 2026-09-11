package applock.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import applock.app.domain.ThemeMode
import applock.app.domain.ThemeSettings

private val DefaultAccent = Color(0xFF6C63FF)

@Composable
fun AppLockTheme(
    settings: ThemeSettings,
    content: @Composable () -> Unit
) {
    val dark = when (settings.mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // ThemeSettings stores a normal Android ARGB Long, not Compose's packed ULong.
    // Converting through Int preserves the ARGB representation safely.
    val primary = runCatching { Color(settings.accent.toInt()) }.getOrDefault(DefaultAccent)

    val scheme = if (dark) {
        darkColorScheme(
            primary = primary,
            secondary = Color(0xFF6DD6FF),
            tertiary = Color(0xFFC879FF),
            background = Color(0xFF07111F),
            surface = Color(0xFF0B1728),
            surfaceVariant = Color(0xFF16263B)
        )
    } else {
        lightColorScheme(
            primary = primary,
            secondary = Color(0xFF006D8D),
            tertiary = Color(0xFF7B3FA0),
            background = Color(0xFFF7F9FC),
            surface = Color.White,
            surfaceVariant = Color(0xFFE9EEF6)
        )
    }

    val radius = settings.cornerRadius.coerceIn(10f, 28f)
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape((radius - 4f).coerceAtLeast(6f).dp),
        medium = RoundedCornerShape(radius.dp),
        large = RoundedCornerShape((radius + 4f).coerceAtMost(32f).dp)
    )

    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(),
        shapes = shapes,
        content = content
    )
}
