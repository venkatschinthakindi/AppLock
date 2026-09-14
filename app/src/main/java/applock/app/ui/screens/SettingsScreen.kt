package applock.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SupportAgent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication

@Composable
fun SettingsScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication

    if (app.repository.hasCredential()) {
        SecurityGateScreen(
            title = "AppLock settings are secured",
            description = "Authenticate before changing system-access paths or security state."
        ) {
            SettingsEditor()
        }
    } else {
        SettingsEditor()
    }
}

@Composable
private fun SettingsEditor() {
    val context = LocalContext.current
    val app = context.applicationContext as AppLockApplication

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SettingsHeader()

        SecurityControlsCard(
            onAccessibilityClick = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            },
            onLockAllClick = {
                app.repository.clearAllUnlocks()
            },
            onAppSettingsClick = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        )

        PrivacySupportCard(
            onPrivacyClick = {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://thrinetratech.in/privacy-policy")
                    )
                )
            }
        )
    }
}

@Composable
private fun SettingsHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "Manage AppLock's system access, protection controls, privacy information, and support.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SecurityControlsCard(
    onAccessibilityClick: () -> Unit,
    onLockAllClick: () -> Unit,
    onAppSettingsClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsSectionTitle(
                icon = {
                    Icon(
                        imageVector = Icons.Default.AdminPanelSettings,
                        contentDescription = null
                    )
                },
                title = "Security & system controls",
                description = "These controls affect how AppLock integrates with Android."
            )

            SettingsAction(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null
                    )
                },
                title = "Accessibility access",
                description = "Manage the Android accessibility service used for foreground-app detection.",
                onClick = onAccessibilityClick
            )

            SettingsAction(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null
                    )
                },
                title = "Lock all protected apps now",
                description = "Immediately clear active unlock sessions for protected apps.",
                onClick = onLockAllClick
            )

            SettingsAction(
                icon = {
                    Icon(
                        imageVector = Icons.Default.OpenInNew,
                        contentDescription = null
                    )
                },
                title = "App system settings",
                description = "Open Android's system settings page for AppLock.",
                onClick = onAppSettingsClick
            )
        }
    }
}

@Composable
private fun PrivacySupportCard(
    onPrivacyClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsSectionTitle(
                icon = {
                    Icon(
                        imageVector = Icons.Default.PrivacyTip,
                        contentDescription = null
                    )
                },
                title = "Privacy & support",
                description = "Information about AppLock's privacy model and how to get help."
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Privacy-first protection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "AppLock does not intentionally transmit PINs, patterns, authentication secrets or private screen contents for the core locking function.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedButton(
                onClick = onPrivacyClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.PrivacyTip,
                    contentDescription = null
                )

                Text(
                    text = "Privacy policy",
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.SupportAgent,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "Support",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Text(
                            text = "contact@thrinetratech.in",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(
    icon: @Composable () -> Unit,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsAction(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        icon()

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}