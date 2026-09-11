package applock.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import applock.app.AppLockApplication

@Composable
fun ProtectedAppsScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    if (app.repository.hasCredential()) {
        SecurityGateScreen(
            title = "Protected apps are secured",
            description = "Authenticate before adding or removing apps from the protection list."
        ) {
            ProtectedAppsEditor()
        }
    } else {
        ProtectedAppsEditor()
    }
}

@Composable
private fun ProtectedAppsEditor() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var apps by remember { mutableStateOf(app.repository.launchableApps()) }
    val shown = apps.filter {
        (filter == "all" || (filter == "protected" && it.protected) || (filter == "unprotected" && !it.protected)) &&
            (it.label.contains(query, true) || it.packageName.contains(query, true))
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Choose the apps AppLock should protect.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text("Search apps") }
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("all" to "All", "protected" to "Protected", "unprotected" to "Unprotected").forEach { (key, label) ->
                FilterChip(selected = filter == key, onClick = { filter = key }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(shown, key = { it.packageName }) { item ->
                ListItem(
                    headlineContent = { Text(item.label) },
                    supportingContent = { Text(if (item.protected) "Protected · ${item.packageName}" else item.packageName) },
                    trailingContent = {
                        Switch(
                            checked = item.protected,
                            onCheckedChange = {
                                app.repository.setProtected(item.packageName, it)
                                apps = app.repository.launchableApps()
                            }
                        )
                    }
                )
            }
        }
    }
}
