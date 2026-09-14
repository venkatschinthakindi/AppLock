package applock.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Security
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
import applock.app.BuildConfig

private const val PRIVACY_POLICY_URL =
    "https://thrinetratech.in/applock-privacy-policy"

private const val SUPPORT_EMAIL =
    "contact@thrinetratech.in"

@Composable
fun AboutScreen() {
    val context = LocalContext.current

    val versionName =
        BuildConfig.VERSION_NAME.ifBlank {
            "Unknown"
        }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(
                horizontal = 16.dp,
                vertical = 14.dp
            ),
        verticalArrangement =
            Arrangement.spacedBy(14.dp)
    ) {
        AboutHeader(
            versionName = versionName
        )

        SecurityCard()

        PrivacyCard(
            onPrivacyClick = {
                openPrivacyPolicy(context)
            }
        )

        SupportCard(
            onSupportClick = {
                openSupportEmail(context)
            }
        )

        LegalFooter(
            versionName = versionName
        )
    }
}

@Composable
private fun AboutHeader(
    versionName: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment =
                    Alignment.CenterVertically,
                horizontalArrangement =
                    Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    modifier = Modifier.size(58.dp),
                    shape = MaterialTheme.shapes.large,
                    color =
                        MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.padding(14.dp),
                        tint =
                            MaterialTheme.colorScheme.onPrimary
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement =
                        Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "App Lock",
                        style =
                            MaterialTheme.typography.headlineSmall,
                        fontWeight =
                            FontWeight.Bold,
                        color =
                            MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Text(
                        text = "Private App Locker",
                        style =
                            MaterialTheme.typography.bodyMedium,
                        color =
                            MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Text(
                text =
                    "Secure, local-first protection for the apps you choose to lock.",
                style =
                    MaterialTheme.typography.bodyMedium,
                color =
                    MaterialTheme.colorScheme.onPrimaryContainer
            )

            Text(
                text = "Version $versionName",
                style =
                    MaterialTheme.typography.labelLarge,
                fontWeight =
                    FontWeight.SemiBold,
                color =
                    MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun SecurityCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = 1.dp
            )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle(
                icon = {
                    Icon(
                        imageVector =
                            Icons.Default.Security,
                        contentDescription = null
                    )
                },
                title = "Security",
                description =
                    "App Lock is designed around local-first authentication and Android security mechanisms."
            )

            SecurityPoint(
                title = "Authentication",
                description =
                    "PIN, pattern and biometric authentication are available according to your device and App Lock configuration."
            )

            SecurityPoint(
                title = "Protected credentials",
                description =
                    "Authentication secrets are protected using Android Keystore-backed security mechanisms."
            )

            SecurityPoint(
                title = "Foreground protection",
                description =
                    "App Lock uses its Android accessibility service to detect protected app transitions and present authentication."
            )
        }
    }
}

@Composable
private fun SecurityPoint(
    title: String,
    description: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement =
            Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            style =
                MaterialTheme.typography.titleSmall,
            fontWeight =
                FontWeight.SemiBold
        )

        Text(
            text = description,
            style =
                MaterialTheme.typography.bodySmall,
            color =
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PrivacyCard(
    onPrivacyClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = 1.dp
            )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle(
                icon = {
                    Icon(
                        imageVector =
                            Icons.Default.Policy,
                        contentDescription = null
                    )
                },
                title = "Privacy",
                description =
                    "Read the complete App Lock privacy policy for details about data access, use, sharing, retention and security."
            )

            OutlinedButton(
                onClick = onPrivacyClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector =
                        Icons.Default.Policy,
                    contentDescription = null
                )

                Text(
                    text = "Open Privacy Policy",
                    modifier =
                        Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun SupportCard(
    onSupportClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation =
            CardDefaults.cardElevation(
                defaultElevation = 1.dp
            )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {
            SectionTitle(
                icon = {
                    Icon(
                        imageVector =
                            Icons.Default.SupportAgent,
                        contentDescription = null
                    )
                },
                title = "Support",
                description =
                    "Need help with App Lock? Contact the support team."
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color =
                    MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment =
                        Alignment.CenterVertically,
                    horizontalArrangement =
                        Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector =
                            Icons.Default.Email,
                        contentDescription = null,
                        tint =
                            MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Text(
                        text = SUPPORT_EMAIL,
                        style =
                            MaterialTheme.typography.bodyMedium,
                        fontWeight =
                            FontWeight.SemiBold,
                        color =
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier =
                            Modifier.weight(1f)
                    )
                }
            }

            Button(
                onClick = onSupportClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector =
                        Icons.Default.SupportAgent,
                    contentDescription = null
                )

                Text(
                    text = "Contact Support",
                    modifier =
                        Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun LegalFooter(
    versionName: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = 4.dp,
                bottom = 12.dp
            ),
        horizontalAlignment =
            Alignment.CenterHorizontally,
        verticalArrangement =
            Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "App Lock",
            style =
                MaterialTheme.typography.labelLarge,
            fontWeight =
                FontWeight.SemiBold
        )

        Text(
            text = "Version $versionName",
            style =
                MaterialTheme.typography.bodySmall,
            color =
                MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "© 2026 Thrinetra Tech",
            style =
                MaterialTheme.typography.bodySmall,
            color =
                MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SectionTitle(
    icon: @Composable () -> Unit,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color =
                MaterialTheme.colorScheme.primaryContainer
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.padding(10.dp),
                contentAlignment =
                    Alignment.Center
            ) {
                icon()
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement =
                Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                style =
                    MaterialTheme.typography.titleMedium,
                fontWeight =
                    FontWeight.SemiBold
            )

            Text(
                text = description,
                style =
                    MaterialTheme.typography.bodySmall,
                color =
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun openPrivacyPolicy(
    context: android.content.Context
) {
    runCatching {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(PRIVACY_POLICY_URL)
            )
        )
    }
}

private fun openSupportEmail(
    context: android.content.Context
) {
    runCatching {
        context.startActivity(
            Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("mailto:$SUPPORT_EMAIL")
            ).apply {
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "App Lock Support"
                )
            }
        )
    }
}