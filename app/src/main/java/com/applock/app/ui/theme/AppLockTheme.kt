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

    // ThemeSettings.accent is stored as an ARGB color value.
    // Color(Int) correctly interprets the value as ARGB.
    val primary = Color(settings.accent.toInt())

    val colorScheme = if (dark) {
        darkColorScheme(
            primary = primary
        )
    } else {
        lightColorScheme(
            primary = primary
        )
    }

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(settings.cornerRadius.dp),
        large = RoundedCornerShape((settings.cornerRadius + 4).dp)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = shapes,
        content = content
    )
}
