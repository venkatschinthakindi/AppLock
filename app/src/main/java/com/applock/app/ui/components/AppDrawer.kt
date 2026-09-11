package applock.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AppDrawer(onDestination: (String) -> Unit) {
    val context = LocalContext.current
    ModalDrawerSheet {
        Spacer(Modifier.height(16.dp))
        Text("App Lock", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Fast protection. Clear health. No compromise.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Spacer(Modifier.height(12.dp))
        NavigationDrawerItem(label = { Text("Home") }, icon = { Icon(Icons.Default.Home, null) }, selected = false, onClick = { onDestination("home") })
        NavigationDrawerItem(label = { Text("Protected apps") }, icon = { Icon(Icons.Default.Lock, null) }, selected = false, onClick = { onDestination("apps") })
        NavigationDrawerItem(label = { Text("Protection Health") }, icon = { Icon(Icons.Default.HealthAndSafety, null) }, selected = false, onClick = { onDestination("health") })
        NavigationDrawerItem(label = { Text("Smart Lock") }, icon = { Icon(Icons.Default.Timer, null) }, selected = false, onClick = { onDestination("smart") })
        NavigationDrawerItem(label = { Text("Customization") }, icon = { Icon(Icons.Default.Palette, null) }, selected = false, onClick = { onDestination("custom") })
        NavigationDrawerItem(label = { Text("Authentication") }, icon = { Icon(Icons.Default.Fingerprint, null) }, selected = false, onClick = { onDestination("security") })
        NavigationDrawerItem(label = { Text("Settings") }, icon = { Icon(Icons.Default.Settings, null) }, selected = false, onClick = { onDestination("settings") })
        NavigationDrawerItem(label = { Text("Upgrade to Pro") }, icon = { Icon(Icons.Default.Star, null) }, selected = false, onClick = { onDestination("pro") })
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("Sponsored by", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 20.dp))
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Atoolix", style = MaterialTheme.typography.titleLarge)
                Text("Practical online tools for everyday tasks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://atoolix.com"))) }) { Text("Visit atoolix.com") }
            }
        }
    }
}
