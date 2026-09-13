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

@Composable
fun DeviceAdminExplanationScreen(
    onEnableProtection: () -> Unit,
    onNotNow: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = 20.dp,
                vertical = 16.dp
            ),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        /*
         * AppLock branding / header.
         */
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier
                    .clip(CircleShape),
                shape = CircleShape,
                color = colors.primaryContainer
            ) {
                Box(
                    modifier = Modifier.padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = colors.primary
                    )
                }
            }

            Spacer(
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Column {
                Text(
                    text = "AppLock",
                    style = typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Security & privacy",
                    style = typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
            }
        }

        /*
         * Main heading.
         */
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Strengthen Your App Protection",
                style = typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Device protection adds an extra OS-level layer that helps make AppLock harder to disable or remove while your protected apps are secured.",
                style = typography.bodyLarge,
                color = colors.onSurfaceVariant
            )
        }

        /*
         * What this helps protect.
         */
        ProtectionExplanationCard(
            title = "What this helps protect",
            icon = Icons.Default.Shield,
            positive = true,
            items = listOf(
                "Prevents unauthorized app-management changes",
                "Adds OS-level tamper protection",
                "Helps protect AppLock from being disabled or removed",
                "Works locally — no internet or account required"
            )
        )

        /*
         * What it does NOT do.
         */
        ProtectionExplanationCard(
            title = "What it does NOT do",
            icon = Icons.Default.VisibilityOff,
            positive = false,
            items = listOf(
                "Does not read your messages",
                "Does not access your personal files",
                "Does not monitor your screen",
                "Does not change or expose your PIN"
            )
        )

        /*
         * Small security architecture note.
         */
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(
                containerColor = colors.surfaceVariant.copy(
                    alpha = 0.65f
                )
            )
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = colors.primary
                )

                Spacer(
                    modifier = Modifier.padding(horizontal = 6.dp)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Your core protection stays local",
                        style = typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Your authentication and lock engine do not depend on ads, billing, analytics, network access or downloaded themes.",
                        style = typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(
            modifier = Modifier.height(2.dp)
        )

        /*
         * Primary action.
         */
        Button(
            onClick = onEnableProtection,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null
            )

            Spacer(
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Text(
                text = "Enable protection",
                fontWeight = FontWeight.SemiBold
            )
        }

        /*
         * Secondary action.
         */
        OutlinedButton(
            onClick = onNotNow,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "Not now"
            )
        }

        Text(
            text = "You can always enable it later from Settings.",
            modifier = Modifier.fillMaxWidth(),
            style = typography.bodySmall,
            color = colors.onSurfaceVariant
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
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (positive) {
                colors.primaryContainer.copy(alpha = 0.72f)
            } else {
                colors.surfaceVariant.copy(alpha = 0.72f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary
                )

                Spacer(
                    modifier = Modifier.padding(horizontal = 5.dp)
                )

                Text(
                    text = title,
                    style = typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            items.forEach { item ->
                Row(
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.padding(top = 2.dp),
                        tint = colors.primary
                    )

                    Spacer(
                        modifier = Modifier.padding(horizontal = 5.dp)
                    )

                    Text(
                        text = item,
                        style = typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}