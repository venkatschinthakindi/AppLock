package applock.app.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication
import applock.app.ui.components.PremiumCard
import applock.app.ui.components.StatusPill

@Composable
fun HomeScreen(
    onNavigate: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication

    val protectedCount = app.repository.protectedPackages().size
    val service = app.repository.accessibilityEnabled()
    val hasAuth = app.repository.hasCredential()

    val status = when {
        !hasAuth -> "Not protected"
        !service -> "Limited protection"
        protectedCount == 0 -> "Ready to configure"
        else -> "Protection active"
    }

    val positive = service && hasAuth && protectedCount > 0

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    )
                )
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f)
            )
        ) {
            Column(
                modifier = Modifier.padding(22.dp)
            ) {

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.padding(6.dp))

                    Text(
                        text = status,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = when {
                        !hasAuth ->
                            "Choose an authentication method before protecting apps."

                        !service ->
                            "Enable Accessibility access so AppLock can detect protected app launches."

                        protectedCount == 0 ->
                            "Select the apps you want AppLock to protect."

                        else ->
                            "$protectedCount apps are protected. Authentication stays local-first."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(14.dp))

                StatusPill(
                    if (positive) "All systems ready" else "Action recommended",
                    positive
                )

                Spacer(Modifier.height(12.dp))

                if (!hasAuth) {
                    Button(
                        onClick = {
                            onNavigate?.invoke("security")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Setup protection")
                    }
                } else if (!service) {
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Enable protection")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            BoxClickableCard(
                modifier = Modifier.weight(1f),
                onClick = { onNavigate?.invoke("apps") }
            ) {
                PremiumCard(
                    title = "Protected apps",
                    subtitle = "$protectedCount selected",
                    icon = Icons.Default.Lock
                )
            }

            BoxClickableCard(
                modifier = Modifier.weight(1f),
                onClick = { onNavigate?.invoke("security") }
            ) {
                PremiumCard(
                    title = "Authentication",
                    subtitle = app.repository
                        .getAuthMethod()
                        .name
                        .lowercase()
                        .replaceFirstChar { it.uppercase() },
                    icon = Icons.Default.Security
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            BoxClickableCard(
                modifier = Modifier.weight(1f),
                onClick = { onNavigate?.invoke("health") }
            ) {
                PremiumCard(
                    title = "Protection Health",
                    subtitle = if (service) "Service available" else "Limited",
                    icon = Icons.Default.HealthAndSafety
                )
            }

            BoxClickableCard(
                modifier = Modifier.weight(1f),
                onClick = { onNavigate?.invoke("smart") }
            ) {
                PremiumCard(
                    title = "Session rule",
                    subtitle = app.repository
                        .getSessionRule()
                        .displayName(),
                    icon = Icons.Default.Settings
                )
            }
        }

        PremiumCard(
            title = "Security first",
            subtitle = "The lock engine is independent of monetization"
        ) {
            Text(
                text = "Authentication does not wait for ads, network requests, billing, analytics, remote configuration or downloaded themes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    )
                }
            ) {
                Text("Review system access")
            }
        }
    }
}

@Composable
private fun BoxClickableCard(
    modifier: Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
    ) {
        content()
    }
}

private fun applock.app.domain.SessionRule.displayName(): String =
    when (this) {
        applock.app.domain.SessionRule.IMMEDIATELY -> "Every launch"
        applock.app.domain.SessionRule.AFTER_LEAVING -> "After leaving"
        applock.app.domain.SessionRule.SCREEN_OFF -> "After screen off"
        applock.app.domain.SessionRule.MINUTES_1 -> "1 minute"
        applock.app.domain.SessionRule.MINUTES_5 -> "5 minutes"
        applock.app.domain.SessionRule.MINUTES_15 -> "15 minutes"
        applock.app.domain.SessionRule.MINUTES_30 -> "30 minutes"
    }