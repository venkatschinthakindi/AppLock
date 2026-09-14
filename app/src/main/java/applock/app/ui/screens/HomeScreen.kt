package applock.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication
import applock.app.security.AntiTamperManager
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
fun HomeScreen(
    onNavigate: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication

    val deviceAdminEnabled =
        AntiTamperManager.isDeviceAdminActive(context)

    val protectedCount =
        app.repository.protectedPackages().size

    val serviceEnabled =
        app.repository.accessibilityEnabled()

    val hasAuth =
        app.repository.hasCredential()

    val allReady =
        deviceAdminEnabled &&
            serviceEnabled &&
            hasAuth &&
            protectedCount > 0

    val statusTitle = when {
        !hasAuth -> "Set up protection"
        !serviceEnabled -> "Protection needs attention"
        protectedCount == 0 -> "Ready to protect"
        else -> "Protection active"
    }

    val statusDescription = when {
        !hasAuth ->
            "Choose an authentication method to secure your protected apps."

        !serviceEnabled ->
            "Accessibility access is needed to detect protected app launches."

        protectedCount == 0 ->
            "Choose the apps you want AppLock to protect."

        else ->
            "$protectedCount ${
                if (protectedCount == 1) "app is" else "apps are"
            } protected. Authentication stays local-first."
    }

    val authName = app.repository
        .getAuthMethod()
        .name
        .lowercase()
        .replaceFirstChar { it.uppercase() }

    val sessionRule =
        app.repository.getSessionRule().displayName()

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val horizontalPadding =
            if (maxWidth < 380.dp) 14.dp else 20.dp

        val cardGap =
            if (maxWidth < 380.dp) 10.dp else 14.dp

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.background,
                                MaterialTheme.colorScheme
                                    .surfaceVariant
                                    .copy(alpha = 0.55f)
                            )
                        )
                    )
                    .padding(
                        horizontal = horizontalPadding,
                        vertical = 14.dp
                    ),
                verticalArrangement =
                    Arrangement.spacedBy(cardGap)
            ) {

                PremiumHeroCard(
                    title = statusTitle,
                    description = statusDescription,
                    ready = allReady,
                    onAction = {
                        when {
                            !hasAuth -> {
                                onNavigate?.invoke("security")
                            }

                            !serviceEnabled -> {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_ACCESSIBILITY_SETTINGS
                                    )
                                )
                            }

                            protectedCount == 0 -> {
                                onNavigate?.invoke("apps")
                            }

                            !deviceAdminEnabled -> {
                                val activity =
                                    context as? Activity

                                if (activity != null) {
                                    AntiTamperManager
                                        .requestDeviceAdmin(activity)
                                }
                            }
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(cardGap)
                ) {
                    DashboardTile(
                        modifier = Modifier.weight(1f),
                        title = "Protected apps",
                        subtitle = "$protectedCount selected",
                        icon = Icons.Default.Lock,
                        iconTint =
                            MaterialTheme.colorScheme.primary,
                        onClick = {
                            onNavigate?.invoke("apps")
                        }
                    )

                    DashboardTile(
                        modifier = Modifier.weight(1f),
                        title = "Authentication",
                        subtitle = authName,
                        icon = Icons.Default.Security,
                        iconTint =
                            MaterialTheme.colorScheme.primary,
                        onClick = {
                            onNavigate?.invoke("security")
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.spacedBy(cardGap)
                ) {
                    DashboardTile(
                        modifier = Modifier.weight(1f),
                        title = "Protection Health",
                        subtitle = if (allReady) {
                            "Fully protected"
                        } else {
                            "Needs attention"
                        },
                        icon = Icons.Default.HealthAndSafety,
                        iconTint = if (allReady) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        onClick = {
                            onNavigate?.invoke("health")
                        }
                    )

                    DashboardTile(
                        modifier = Modifier.weight(1f),
                        title = "Session rule",
                        subtitle = sessionRule,
                        icon = Icons.Default.Settings,
                        iconTint =
                            MaterialTheme.colorScheme.primary,
                        onClick = {
                            onNavigate?.invoke("smart")
                        }
                    )
                }

                SecurityFirstCard(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_ACCESSIBILITY_SETTINGS
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun PremiumHeroCard(
    title: String,
    description: String,
    ready: Boolean,
    onAction: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            primary,
                            secondary,
                            primary.copy(alpha = 0.84f)
                        )
                    )
                )
                .padding(
                    horizontal = 20.dp,
                    vertical = 20.dp
                )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {

                // Top identity row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(
                                Color.White.copy(alpha = 0.15f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (ready) {
                                Icons.Default.Shield
                            } else {
                                Icons.Default.Lock
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(27.dp)
                        )
                    }

                    Spacer(Modifier.width(13.dp))

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )

                        Spacer(Modifier.height(3.dp))

                        Text(
                            text = if (ready) {
                                "Your protected apps are secured"
                            } else {
                                "Your protection setup needs attention"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.82f)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Description — full available width
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(15.dp))

                // Status badge
                Surface(
                    shape = RoundedCornerShape(50.dp),
                    color = Color.White.copy(alpha = 0.14f)
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = 11.dp,
                            vertical = 7.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    Color.White
                                )
                        )

                        Spacer(Modifier.width(7.dp))

                        Text(
                            text = if (ready) {
                                "All systems ready"
                            } else {
                                "Review protection"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

                // Action area
                if (!ready) {
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = onAction,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "Review protection",
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(Modifier.width(6.dp))

                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardTile(
    modifier: Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    iconTint: Color,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(
            onClick = onClick
        ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
                    .copy(alpha = 0.96f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp)
                .padding(14.dp)
        ) {

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                iconTint.copy(alpha = 0.13f),
                                MaterialTheme.colorScheme
                                    .surfaceVariant
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(
                        start = 62.dp,
                        end = 18.dp
                    )
            ) {
                Text(
                    text = title,
                    style =
                        MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color =
                        MaterialTheme.colorScheme.onSurface
                )

                Spacer(Modifier.height(3.dp))

                Text(
                    text = subtitle,
                    style =
                        MaterialTheme.typography.bodySmall,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant
                )
            }

            Icon(
                imageVector =
                    Icons.Default.ChevronRight,
                contentDescription = null,
                tint =
                    MaterialTheme.colorScheme.primary
                        .copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(20.dp)
            )
        }
    }
}

@Composable
private fun SecurityFirstCard(
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
                    .copy(alpha = 0.97f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme
                                .primary
                                .copy(alpha = 0.10f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector =
                            Icons.Default.Shield,
                        contentDescription = null,
                        tint =
                            MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(25.dp)
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Security first",
                        style =
                            MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(2.dp))

                    Text(
                        text =
                            "The lock engine is independent of monetization.",
                        style =
                            MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant
                    )
                }

                Icon(
                    imageVector =
                        Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint =
                        MaterialTheme.colorScheme.primary
                            .copy(alpha = 0.7f)
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text =
                    "Authentication does not wait for ads, network requests, billing, analytics, remote configuration or downloaded themes.",
                style =
                    MaterialTheme.typography.bodyMedium,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))

            OutlinedButton(
                onClick = onClick,
                shape = RoundedCornerShape(18.dp)
            ) {
                Icon(
                    imageVector =
                        Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(Modifier.width(7.dp))

                Text("Review system access")
            }
        }
    }
}

private fun applock.app.domain.SessionRule.displayName(): String =
    when (this) {
        applock.app.domain.SessionRule.IMMEDIATELY ->
            "Every launch"

        applock.app.domain.SessionRule.AFTER_LEAVING ->
            "After leaving"

        applock.app.domain.SessionRule.SCREEN_OFF ->
            "After screen off"

        applock.app.domain.SessionRule.MINUTES_1 ->
            "1 minute"

        applock.app.domain.SessionRule.MINUTES_5 ->
            "5 minutes"

        applock.app.domain.SessionRule.MINUTES_15 ->
            "15 minutes"

        applock.app.domain.SessionRule.MINUTES_30 ->
            "30 minutes"
    }