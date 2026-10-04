package applock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ProtectionModeSelectionScreen(
    deviceOwnerActive: Boolean,
    enhancedProvisioningInFlight: Boolean,
    enhancedProvisioningUnavailable: Boolean,
    onStandardSelected: () -> Unit,
    onEnhancedSelected: () -> Unit
) {

    val colors =
        MaterialTheme.colorScheme

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    colors.background
                )
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(
                    horizontal = 20.dp,
                    vertical = 24.dp
                ),

        verticalArrangement =
            Arrangement.spacedBy(
                16.dp
            )
    ) {

        /*
         * --------------------------------------------------------------
         * HEADER
         * --------------------------------------------------------------
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

                shape =
                    CircleShape,

                color =
                    colors.primaryContainer
            ) {

                Icon(

                    imageVector =
                        Icons.Default.Security,

                    contentDescription =
                        null,

                    tint =
                        colors.primary,

                    modifier =
                        Modifier.padding(
                            14.dp
                        )
                )
            }

            Spacer(
                modifier =
                    Modifier.width(
                        12.dp
                    )
            )

            Column {

                Text(

                    text =
                        "Protection mode",

                    style =
                        MaterialTheme
                            .typography
                            .titleLarge,

                    fontWeight =
                        FontWeight.Bold
                )

                Text(

                    text =
                        "Choose how AppLock should protect your apps",

                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium,

                    color =
                        colors.onSurfaceVariant
                )
            }
        }

        /*
         * --------------------------------------------------------------
         * INTRO
         * --------------------------------------------------------------
         */
        Column(

            verticalArrangement =
                Arrangement.spacedBy(
                    6.dp
                )
        ) {

            Text(

                text =
                    "Choose your protection level",

                style =
                    MaterialTheme
                        .typography
                        .headlineMedium,

                fontWeight =
                    FontWeight.Bold
            )

            Text(

                text =
                    "Standard Protection uses AppLock's normal authentication and accessibility protection. Enhanced Protection adds Android Device Owner authority when the device supports OS-managed provisioning.",

                style =
                    MaterialTheme
                        .typography
                        .bodyLarge,

                color =
                    colors.onSurfaceVariant
            )
        }

        /*
         * --------------------------------------------------------------
         * STANDARD PROTECTION
         * --------------------------------------------------------------
         */
        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    22.dp
                ),

            colors =
                CardDefaults
                    .cardColors(
                        containerColor =
                            colors.surfaceVariant
                    )
        ) {

            Column(

                modifier =
                    Modifier.padding(
                        18.dp
                    ),

                verticalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {

                ModeHeader(

                    icon =
                        Icons.Default.Security,

                    title =
                        "Standard Protection",

                    subtitle =
                        "Normal AppLock protection"
                )

                ProtectionPoint(
                    "Foreground-app protection"
                )

                ProtectionPoint(
                    "Privacy barrier during authentication"
                )

                ProtectionPoint(
                    "PIN, pattern and biometric authentication"
                )

                ProtectionPoint(
                    "No Device Owner provisioning required"
                )

                OutlinedButton(

                    onClick =
                        onStandardSelected,

                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(
                        "Use Standard Protection"
                    )
                }
            }
        }

        /*
         * --------------------------------------------------------------
         * ENHANCED PROTECTION
         * --------------------------------------------------------------
         */
        Card(

            modifier =
                Modifier.fillMaxWidth(),

            shape =
                RoundedCornerShape(
                    22.dp
                ),

            colors =
                CardDefaults
                    .cardColors(
                        containerColor =
                            colors.primaryContainer
                    )
        ) {

            Column(

                modifier =
                    Modifier.padding(
                        18.dp
                    ),

                verticalArrangement =
                    Arrangement.spacedBy(
                        12.dp
                    )
            ) {

                ModeHeader(

                    icon =
                        Icons.Default.AdminPanelSettings,

                    title =
                        "Enhanced Protection",

                    subtitle =
                        "Android Device Owner protection",

                    contentColor =
                        colors.onPrimaryContainer
                )

                /*
                 * ------------------------------------------------------
                 * DEVICE OWNER STATUS
                 * ------------------------------------------------------
                 */
                Surface(

                    modifier =
                        Modifier.fillMaxWidth(),

                    shape =
                        RoundedCornerShape(
                            14.dp
                        ),

                    color =
                        if (deviceOwnerActive) {

                            colors.secondaryContainer

                        } else {

                            colors.surface.copy(
                                alpha = 0.72f
                            )
                        }
                ) {

                    Row(

                        modifier =
                            Modifier.padding(
                                12.dp
                            ),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        Icon(

                            imageVector =
                                if (deviceOwnerActive) {

                                    Icons.Default.CheckCircle

                                } else {

                                    Icons.Default.AdminPanelSettings
                                },

                            contentDescription =
                                null
                        )

                        Spacer(
                            modifier =
                                Modifier.width(
                                    10.dp
                                )
                        )

                        Column {

                            Text(

                                text =
                                    if (deviceOwnerActive) {

                                        "Device Owner is active"

                                    } else {

                                        "Device Owner is not active"
                                    },

                                fontWeight =
                                    FontWeight.SemiBold
                            )

                            if (!deviceOwnerActive) {

                                Text(

                                    text =
                                        "Enhanced Protection requires Android Device Owner authority.",

                                    style =
                                        MaterialTheme
                                            .typography
                                            .bodySmall,

                                    color =
                                        colors.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                ProtectionPoint(

                    text =
                        "Everything in Standard Protection",

                    contentColor =
                        colors.onPrimaryContainer
                )

                ProtectionPoint(

                    text =
                        "OS-level package suspension when Device Owner is active",

                    contentColor =
                        colors.onPrimaryContainer
                )

                ProtectionPoint(

                    text =
                        "Stronger protection against launching protected apps",

                    contentColor =
                        colors.onPrimaryContainer
                )

                /*
                 * ------------------------------------------------------
                 * UNAVAILABLE STATE
                 * ------------------------------------------------------
                 */
                if (
                    enhancedProvisioningUnavailable &&
                    !deviceOwnerActive
                ) {

                    Surface(

                        modifier =
                            Modifier.fillMaxWidth(),

                        shape =
                            RoundedCornerShape(
                                14.dp
                            ),

                        color =
                            colors.errorContainer
                    ) {

                        Column(

                            modifier =
                                Modifier.padding(
                                    14.dp
                                ),

                            verticalArrangement =
                                Arrangement.spacedBy(
                                    6.dp
                                )
                        ) {

                            Text(

                                text =
                                    "Enhanced Protection is unavailable on this device",

                                fontWeight =
                                    FontWeight.Bold,

                                color =
                                    colors.onErrorContainer
                            )

                            Text(

                                text =
                                    "Android must provision AppLock as Device Owner before Enhanced Protection can be enabled. This cannot be completed from an already-provisioned Android device.",

                                style =
                                    MaterialTheme
                                        .typography
                                        .bodySmall,

                                color =
                                    colors.onErrorContainer
                            )
                        }
                    }
                }

                /*
                 * ------------------------------------------------------
                 * ACTION
                 * ------------------------------------------------------
                 */
                Button(

                    onClick =
                        onEnhancedSelected,

                    enabled =
                        deviceOwnerActive ||
                            (
                                !enhancedProvisioningInFlight &&
                                    !enhancedProvisioningUnavailable
                                ),

                    modifier =
                        Modifier.fillMaxWidth()
                ) {

                    Text(

                        text =
                            when {

                                deviceOwnerActive ->
                                    "Use Enhanced Protection"

                                enhancedProvisioningInFlight ->
                                    "Setting up Enhanced Protection…"

                                enhancedProvisioningUnavailable ->
                                    "Enhanced Protection Unavailable"

                                else ->
                                    "Enable Enhanced Protection"
                            }
                    )
                }
            }
        }

        /*
         * --------------------------------------------------------------
         * EXPLANATION
         * --------------------------------------------------------------
         */
        Text(

            text =
                if (deviceOwnerActive) {

                    "AppLock has the required Device Owner authority. Enhanced Protection can use Android's device-management APIs."

                } else {

                    "Enhanced Protection is different from Device Admin. AppLock cannot silently turn Device Admin into Device Owner. Android must provision Device Owner authority through the supported provisioning process."
                },

            style =
                MaterialTheme
                    .typography
                    .bodySmall,

            color =
                colors.onSurfaceVariant,

            modifier =
                Modifier.padding(
                    horizontal = 4.dp
                )
        )
    }
}

@Composable
private fun ModeHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    contentColor: Color =
        MaterialTheme
            .colorScheme
            .onSurface
) {

    Row(

        modifier =
            Modifier.fillMaxWidth(),

        verticalAlignment =
            Alignment.CenterVertically
    ) {

        Surface(

            shape =
                RoundedCornerShape(
                    14.dp
                ),

            color =
                MaterialTheme
                    .colorScheme
                    .primary
                    .copy(
                        alpha = 0.12f
                    )
        ) {

            Icon(

                imageVector =
                    icon,

                contentDescription =
                    null,

                tint =
                    MaterialTheme
                        .colorScheme
                        .primary,

                modifier =
                    Modifier.padding(
                        10.dp
                    )
            )
        }

        Spacer(
            modifier =
                Modifier.width(
                    12.dp
                )
        )

        Column(
            modifier =
                Modifier.weight(1f)
        ) {

            Text(

                text =
                    title,

                style =
                    MaterialTheme
                        .typography
                        .titleMedium,

                fontWeight =
                    FontWeight.Bold,

                color =
                    contentColor
            )

            Text(

                text =
                    subtitle,

                style =
                    MaterialTheme
                        .typography
                        .bodySmall,

                color =
                    contentColor.copy(
                        alpha = 0.75f
                    )
            )
        }
    }
}

@Composable
private fun ProtectionPoint(
    text: String,
    contentColor: Color =
        MaterialTheme
            .colorScheme
            .onSurface
) {

    Row(

        modifier =
            Modifier.fillMaxWidth(),

        verticalAlignment =
            Alignment.Top
    ) {

        Icon(

            imageVector =
                Icons.Default.Shield,

            contentDescription =
                null,

            tint =
                MaterialTheme
                    .colorScheme
                    .primary
        )

        Spacer(
            modifier =
                Modifier.width(
                    8.dp
                )
        )

        Text(

            text =
                text,

            style =
                MaterialTheme
                    .typography
                    .bodyMedium,

            color =
                contentColor
        )
    }
}