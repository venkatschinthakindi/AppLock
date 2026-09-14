package applock.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import applock.app.R
import android.graphics.drawable.Drawable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.core.graphics.drawable.toBitmap

private const val PRIVACY_POLICY_URL =
    "https://thrinetratech.in/applock-privacy-policy"

private const val SUPPORT_EMAIL =
    "contact@thrinetratech.in"

@Composable
fun AppDrawer(
    currentDestination: String,
    onDestination: (String) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(top = 18.dp, bottom = 18.dp)
        ) {
            DrawerHeader()

            Spacer(
                modifier = Modifier.height(14.dp)
            )

            val items = listOf(
                Triple(
                    "home",
                    "Home",
                    Icons.Default.Home
                ),
                Triple(
                    "apps",
                    "Protected apps",
                    Icons.Default.Lock
                ),
                Triple(
                    "health",
                    "Protection Health",
                    Icons.Default.HealthAndSafety
                ),
                Triple(
                    "smart",
                    "Smart Lock",
                    Icons.Default.Timer
                ),
                Triple(
                    "custom",
                    "Customization",
                    Icons.Default.Palette
                ),
                Triple(
                    "security",
                    "Authentication",
                    Icons.Default.Security
                ),
                Triple(
                    "settings",
                    "Settings",
                    Icons.Default.Settings
                )
            )

            items.forEach { (key, label, icon) ->
                NavigationDrawerItem(
                    label = {
                        Text(
                            text = label,
                            fontWeight = if (currentDestination == key) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            }
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = icon,
                            contentDescription = null
                        )
                    },
                    selected = currentDestination == key,
                    onClick = {
                        onDestination(key)
                    },
                    modifier = Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 2.dp
                    )
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(
                    vertical = 14.dp,
                    horizontal = 16.dp
                )
            )

            // Privacy Policy
            NavigationDrawerItem(
                label = {
                    Text(
                        text = "Privacy Policy",
                        fontWeight = FontWeight.Normal
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Policy,
                        contentDescription = null
                    )
                },
                selected = false,
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(PRIVACY_POLICY_URL)
                            )
                        )
                    }
                },
                modifier = Modifier.padding(
                    horizontal = 12.dp,
                    vertical = 2.dp
                )
            )

            // Support
            NavigationDrawerItem(
                label = {
                    Text(
                        text = "Support",
                        fontWeight = FontWeight.Normal
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.SupportAgent,
                        contentDescription = null
                    )
                },
                selected = false,
                onClick = {
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
                },
                modifier = Modifier.padding(
                    horizontal = 12.dp,
                    vertical = 2.dp
                )
            )

            // About
            NavigationDrawerItem(
                label = {
                    Text(
                        text = "About AppLock",
                        fontWeight = if (currentDestination == "about") {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        }
                    )
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null
                    )
                },
                selected = currentDestination == "about",
                onClick = {
                    onDestination("about")
                },
                modifier = Modifier.padding(
                    horizontal = 12.dp,
                    vertical = 2.dp
                )
            )

            HorizontalDivider(
                modifier = Modifier.padding(
                    vertical = 14.dp,
                    horizontal = 16.dp
                )
            )

            SponsorSection(
                onVisit = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://atoolix.com")
                            )
                        )
                    }
                }
            )

            Spacer(
                modifier = Modifier.height(6.dp)
            )
        }
    }
}

@Composable
private fun AppLockIconPainter(): BitmapPainter {
    val context = LocalContext.current

    return remember(context) {
        val drawable: Drawable =
            context.packageManager.getApplicationIcon(
                context.applicationInfo
            )

        BitmapPainter(
            drawable
                .toBitmap(
                    width = 192,
                    height = 192
                )
                .asImageBitmap()
        )
    }
}

@Composable
private fun DrawerHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Image(
            painter = AppLockIconPainter(),
            contentDescription = "AppLock logo",
            modifier = Modifier
                .size(52.dp)
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = "AppLock – Private App Locker",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Fast protection. Beautiful unlocking.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SponsorSection(
    onVisit: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "SPONSORED",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
                    .copy(alpha = 0.72f)
            ),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 1.dp
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SponsorIdentity()

                Text(
                    text = "Useful tools for everyday tasks, available online.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = onVisit,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Visit Atoolix")
                }
            }
        }
    }
}

@Composable
private fun SponsorIdentity() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.size(64.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(
                        id = R.drawable.atoolix_logo
                    ),
                    contentDescription = "Atoolix",
                    modifier = Modifier
                        .size(64.dp)
                        .padding(7.dp),
                    contentScale = ContentScale.Fit
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Atoolix",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Practical online tools",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}