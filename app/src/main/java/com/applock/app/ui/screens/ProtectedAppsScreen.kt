package applock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication

@Composable
fun ProtectedAppsScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf(app.repository.launchableApps()) }
    val shown = apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search apps") })
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.packageName }) { item ->
                ListItem(headlineContent = { Text(item.label) }, supportingContent = { Text(item.packageName) }, trailingContent = { Switch(checked = item.protected, onCheckedChange = { app.repository.setProtected(item.packageName, it); apps = app.repository.launchableApps() }) })
            }
        }
    }
}
