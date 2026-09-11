package applock.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AppDrawer(currentDestination: String, onDestination: (String) -> Unit) {
    val context = LocalContext.current
    ModalDrawerSheet {
        Column(Modifier.padding(top = 22.dp)) {
            Text("AppLock", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
            Text("Fast protection. Beautiful unlocking.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            Spacer(Modifier.height(14.dp))
            val items = listOf(
                Triple("home", "Home", Icons.Default.Home),
                Triple("apps", "Protected apps", Icons.Default.Lock),
                Triple("health", "Protection Health", Icons.Default.HealthAndSafety),
                Triple("smart", "Smart Lock", Icons.Default.Timer),
                Triple("custom", "Customization", Icons.Default.Palette),
                Triple("security", "Authentication", Icons.Default.Security),
                Triple("settings", "Settings", Icons.Default.Settings),
                Triple("pro", "Upgrade to Pro", Icons.Default.Star)
            )
            items.forEach { (key, label, icon) ->
                NavigationDrawerItem(
                    label = { Text(label) },
                    icon = { androidx.compose.material3.Icon(icon, null) },
                    selected = currentDestination == key,
                    onClick = { onDestination(key) },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp, horizontal = 16.dp))
            Text("Sponsored", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 20.dp))
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Atoolix", style = MaterialTheme.typography.titleLarge)
                    Text("Practical online tools for everyday tasks.", color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://atoolix.com"))) }) { Text("Visit Atoolix") }
                }
            }
        }
    }
}
