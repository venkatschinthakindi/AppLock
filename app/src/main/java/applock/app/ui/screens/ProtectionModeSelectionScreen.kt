package applock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ProtectionModeSelectionScreen(
    deviceAdminActive: Boolean,
    onStandardSelected: () -> Unit,
    onEnhancedSelected: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.clip(CircleShape), shape = CircleShape,
                color = colors.primaryContainer) {
                Icon(Icons.Default.Security, null, tint = colors.primary,
                    modifier = Modifier.padding(14.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Protection mode", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text("Choose how AppLock should protect your apps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Choose your protection level",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold)
            Text("Both modes use AppLock's normal locking and authentication flow. Enhanced Protection adds Android Device Admin when it is enabled.",
                style = MaterialTheme.typography.bodyLarge,
                color = colors.onSurfaceVariant)
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLow)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeHeader(Icons.Default.Security, "Standard Protection",
                    "Accessibility-based app protection")
                ProtectionPoint("Foreground-app protection")
                ProtectionPoint("Privacy barrier during authentication")
                ProtectionPoint("PIN, pattern and biometric authentication")
                ProtectionPoint("No Device Admin required")
                OutlinedButton(onStandardSelected, Modifier.fillMaxWidth()) {
                    Text("Use Standard Protection")
                }
            }
        }

        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = colors.primaryContainer)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeHeader(Icons.Default.AdminPanelSettings, "Enhanced Protection",
                    "Accessibility + Android Device Admin",
                    colors.onPrimaryContainer)
                Surface(Modifier.fillMaxWidth(), RoundedCornerShape(14.dp),
                    color = if (deviceAdminActive) colors.secondaryContainer
                    else colors.surface.copy(alpha = .72f)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (deviceAdminActive) Icons.Default.CheckCircle
                        else Icons.Default.AdminPanelSettings, null)
                        Spacer(Modifier.width(10.dp))
                        Text(if (deviceAdminActive) "Device Admin is currently enabled"
                        else "Device Admin is not enabled yet",
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                ProtectionPoint("Everything in Standard Protection", colors.onPrimaryContainer)
                ProtectionPoint("Additional Device Admin protection when active", colors.onPrimaryContainer)
                ProtectionPoint("Helps make AppLock management and removal harder to change", colors.onPrimaryContainer)
                Button(onEnhancedSelected, Modifier.fillMaxWidth()) {
                    Text(if (deviceAdminActive) "Use Enhanced Protection"
                    else "Enable & Use Enhanced Protection")
                }
            }
        }

        Text("Device Admin is controlled by Android. AppLock cannot activate it silently. If you choose Enhanced Protection without Device Admin enabled, Android's confirmation screen will be shown first.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
private fun ModeHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = .12f)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(10.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold, color = contentColor)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = .75f))
        }
    }
}

@Composable
private fun ProtectionPoint(
    text: String,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Shield, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = contentColor)
    }
}
