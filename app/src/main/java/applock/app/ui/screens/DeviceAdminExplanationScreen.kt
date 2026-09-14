package applock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width

@Composable
fun DeviceAdminExplanationScreen(
    onEnableProtection: () -> Unit,
    onNotNow: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 20.dp,
                    vertical = 16.dp
                ),
        verticalArrangement =
            Arrangement.spacedBy(16.dp)
    ) {

        /*
         * Header.
         */
        Row(
            modifier =
                Modifier.fillMaxWidth(),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Surface(
                modifier =
                    Modifier.clip(
                        CircleShape
                    ),
                shape = CircleShape,
                color =
                    colors.primaryContainer
            ) {

                Box(
                    modifier =
                        Modifier.padding(
                            14.dp
                        ),
                    contentAlignment =
                        Alignment.Center
                ) {

                    Icon(
                        imageVector =
                            Icons.Default.Lock,
                        contentDescription =
                            null,
                        tint =
                            colors.primary
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.widthSpacer(8.dp)
            )

            Column {

                Text(
                    text =
                        "AppLock – Private App Locker",
                    style =
                        typography.titleLarge,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    text =
                        "Device protection",
                    style =
                        typography.bodyMedium,
                    color =
                        colors.onSurfaceVariant
                )
            }
        }

        /*
         * Main explanation.
         */
        Column(
            verticalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            Text(
                text =
                    "Strengthen AppLock protection",
                style =
                    typography.headlineMedium,
                fontWeight =
                    FontWeight.Bold
            )

            Text(
                text =
                    "Android Device Admin adds an OS-level security " +
                        "layer that helps make AppLock harder to disable " +
                        "or remove.",
                style =
                    typography.bodyLarge,
                color =
                    colors.onSurfaceVariant
            )
        }

        ProtectionExplanationCard(
            title =
                "Why enable it?",
            icon =
                Icons.Default.Shield,
            positive = true,
            items =
                listOf(
                    "Adds an additional OS-level protection layer",
                    "Helps protect AppLock from unauthorized removal",
                    "Makes security configuration harder to tamper with",
                    "Works locally without an account or internet connection"
                )
        )

        ProtectionExplanationCard(
            title =
                "What Device Admin does NOT mean",
            icon =
                Icons.Default.VisibilityOff,
            positive = false,
            items =
                listOf(
                    "It does not read your messages",
                    "It does not read your photos or personal files",
                    "It does not monitor your screen",
                    "It does not expose your AppLock PIN or pattern"
                )
        )

        Card(
            modifier =
                Modifier.fillMaxWidth(),
            shape =
                RoundedCornerShape(22.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        colors.surfaceVariant.copy(
                            alpha = 0.65f
                        )
                )
        ) {

            Row(
                modifier =
                    Modifier.padding(18.dp),
                verticalAlignment =
                    Alignment.Top
            ) {

                Icon(
                    imageVector =
                        Icons.Default.Security,
                    contentDescription =
                        null,
                    tint =
                        colors.primary
                )

                Spacer(
                    modifier =
                        Modifier.widthSpacer(8.dp)
                )

                Column(
                    verticalArrangement =
                        Arrangement.spacedBy(4.dp)
                ) {

                    Text(
                        text =
                            "Your authentication remains local",
                        style =
                            typography.titleSmall,
                        fontWeight =
                            FontWeight.SemiBold
                    )

                    Text(
                        text =
                            "PIN, pattern, biometric authentication " +
                                "and the lock engine remain independent " +
                                "of ads, billing, analytics and network services.",
                        style =
                            typography.bodyMedium,
                        color =
                            colors.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(
            modifier =
                Modifier.height(2.dp)
        )

        Button(
            onClick =
                onEnableProtection,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            shape =
                RoundedCornerShape(16.dp)
        ) {

            Icon(
                imageVector =
                    Icons.Default.Lock,
                contentDescription =
                    null
            )

            Spacer(
                modifier =
                    Modifier.widthSpacer(8.dp)
            )

            Text(
                text =
                    "Enable device protection",
                fontWeight =
                    FontWeight.SemiBold
            )
        }

        OutlinedButton(
            onClick =
                onNotNow,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            shape =
                RoundedCornerShape(16.dp)
        ) {

            Text(
                text =
                    "Not now"
            )
        }

        Text(
            text =
                "Android will show a system confirmation screen. " +
                    "AppLock cannot silently activate Device Admin. " +
                    "You can enable it later from AppLock Settings.",
            modifier =
                Modifier.fillMaxWidth(),
            style =
                typography.bodySmall,
            color =
                colors.onSurfaceVariant
        )
    }
}

@Composable
private fun ProtectionExplanationCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    positive: Boolean,
    items: List<String>
) {

    val colors =
        MaterialTheme.colorScheme

    val typography =
        MaterialTheme.typography

    Card(
        modifier =
            Modifier.fillMaxWidth(),
        shape =
            RoundedCornerShape(22.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (positive) {
                        colors.primaryContainer.copy(
                            alpha = 0.72f
                        )
                    } else {
                        colors.surfaceVariant.copy(
                            alpha = 0.72f
                        )
                    }
            )
    ) {

        Column(
            modifier =
                Modifier.padding(18.dp),
            verticalArrangement =
                Arrangement.spacedBy(12.dp)
        ) {

            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                Icon(
                    imageVector =
                        icon,
                    contentDescription =
                        null,
                    tint =
                        colors.primary
                )

                Spacer(
                    modifier =
                        Modifier.widthSpacer(8.dp)
                )

                Text(
                    text =
                        title,
                    style =
                        typography.titleMedium,
                    fontWeight =
                        FontWeight.Bold
                )
            }

            items.forEach { item ->

                Row(
                    verticalAlignment =
                        Alignment.Top
                ) {

                    Icon(
                        imageVector =
                            Icons.Default.Check,
                        contentDescription =
                            null,
                        tint =
                            colors.primary
                    )

                    Spacer(
                        modifier =
                            Modifier.widthSpacer(8.dp)
                    )

                    Text(
                        text =
                            item,
                        style =
                            typography.bodyMedium,
                        color =
                            colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/*
 * Small layout helper keeps the replacement readable.
 */
private fun Modifier.widthSpacer(
    width: androidx.compose.ui.unit.Dp
): Modifier =
    this.then(
        Modifier
            .padding(
                horizontal = width / 2
            )
    )