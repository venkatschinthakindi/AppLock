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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication
import applock.app.data.LaunchableAppCatalog
import applock.app.service.AppDetectionAccessibilityService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay

@Composable
fun ProtectedAppsScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication

    if (!app.repository.hasCredential()) {
        ProtectedAppsEditor()
        return
    }

    /*
     * Use the current security gate from ui.lock explicitly.
     *
     * The previous implementation resolved SecurityGateScreen from this
     * package (ui.screens), which was an older gate API and could display
     * "Current PIN" even when the configured authentication method was
     * Pattern.
     *
     * The current SecurityGateScreen owns authentication-method selection
     * through AppLockRepository and reports success through this callback.
     */
    var authenticated by remember {
        mutableStateOf(false)
    }

    if (authenticated) {
        ProtectedAppsEditor()
    } else {
        applock.app.ui.lock.SecurityGateScreen(
            onAuthenticated = {
                authenticated = true
            }
        )
    }
}

@Composable
private fun ProtectedAppsEditor() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    val lifecycleOwner = LocalLifecycleOwner.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var apps by remember { mutableStateOf(emptyList<applock.app.domain.ProtectedApp>()) }

    fun refreshApps() {
        app.repository.refreshProtectionState()
        apps = LaunchableAppCatalog.load(
            context = app,
            protectedPackages = app.repository.protectedPackages()
        )
    }

    // Refresh on entry, shortly after entry, and every time AppLock returns
    // to the foreground. This keeps the launcher-visible catalog current after
    // installs, uninstalls, updates and launcher changes without QUERY_ALL_PACKAGES.
    LaunchedEffect(Unit) {
        refreshApps()
        delay(350L)
        refreshApps()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshApps()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val shown = apps.filter {
        (filter == "all" ||
            (filter == "protected" && it.protected) ||
            (filter == "unprotected" && !it.protected)) &&
            (it.label.contains(query, true) || it.packageName.contains(query, true))
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Choose the apps AppLock should protect.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
            listOf(
                "all" to "All",
                "protected" to "Protected",
                "unprotected" to "Unprotected"
            ).forEach { (key, label) ->
                FilterChip(
                    selected = filter == key,
                    onClick = { filter = key },
                    label = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(shown, key = { it.packageName }) { item ->
                ListItem(
                    headlineContent = { Text(item.label) },
                    supportingContent = {
                        Text(
                            if (item.protected) "Protected · ${item.packageName}"
                            else item.packageName
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = item.protected,
                            onCheckedChange = { enabled ->
                                app.repository.setProtected(item.packageName, enabled)
                                // Protection-list changes are security-state
                                // boundaries. Do not let an old foreground
                                // authorization survive a settings change.
                                app.lockEngine.resetTransitionState()
                                AppDetectionAccessibilityService.notifySecurityConfigurationChanged()
                                refreshApps()
                            }
                        )
                    }
                )
            }
        }
    }
}
