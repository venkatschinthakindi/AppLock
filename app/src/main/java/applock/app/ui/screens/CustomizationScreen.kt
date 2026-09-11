package applock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication
import applock.app.domain.AnimationStyle
import applock.app.domain.ThemeMode
import applock.app.ui.components.PremiumCard

@Composable
fun CustomizationScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    val theme by app.repository.theme.collectAsState()

    val accents = listOf(
        0xFF6C63FFL to "Violet",
        0xFF00A8E8L to "Ocean",
        0xFF00A878L to "Emerald",
        0xFFFF6B6BL to "Coral",
        0xFFFF9F1CL to "Amber",
        0xFF9B5DE5L to "Plum"
    )

    Column(
        Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            "One theme engine, everywhere",
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            "Changes apply consistently to the dashboard, settings and lock experience.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        PremiumCard(
            title = "Theme mode",
            subtitle = "Light, dark or follow Android"
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = theme.mode == mode,
                        onClick = {
                            app.repository.updateTheme {
                                it.copy(mode = mode)
                            }
                        },
                        label = {
                            Text(
                                mode.name
                                    .lowercase()
                                    .replaceFirstChar { it.uppercase() }
                            )
                        }
                    )
                }
            }
        }

        PremiumCard(
            title = "Accent color",
            subtitle = "Used for primary actions and status emphasis"
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                accents.forEach { (value, label) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(value.toInt()))
                                .clickable {
                                    app.repository.updateTheme {
                                        it.copy(accent = value)
                                    }
                                }
                        )

                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        PremiumCard(
            title = "Corner radius",
            subtitle = "Tune the visual softness"
        ) {
            Slider(
                value = theme.cornerRadius,
                onValueChange = { value ->
                    app.repository.updateTheme { settings ->
                        settings.copy(cornerRadius = value)
                    }
                },
                valueRange = 10f..28f,
                steps = 8
            )

            Text(
                "${theme.cornerRadius.toInt()} dp",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        PremiumCard(
            title = "Unlock motion",
            subtitle = "Animation beautifies the experience but never delays authentication"
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimationStyle.entries.forEach { style ->
                    val enabled = style != AnimationStyle.CRYSTAL_UNLOCK

                    FilterChip(
                        selected = theme.animationStyle == style,
                        enabled = enabled,
                        onClick = {
                            app.repository.updateTheme {
                                it.copy(animationStyle = style)
                            }
                        },
                        label = {
                            Text(
                                if (style == AnimationStyle.CRYSTAL_UNLOCK) {
                                    "Crystal · Pro"
                                } else {
                                    style.name
                                        .replace('_', ' ')
                                        .lowercase()
                                        .replaceFirstChar { it.uppercase() }
                                }
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier.weight(1f)
                ) {
                    Text("Reduced motion")

                    Text(
                        "Prefer minimal movement",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = theme.reducedMotion,
                    onCheckedChange = { checked ->
                        app.repository.updateTheme {
                            it.copy(reducedMotion = checked)
                        }
                    }
                )
            }
        }
    }
}