package applock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication
import applock.app.domain.SessionRule

@Composable
fun SmartLockScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    var rule by remember { mutableStateOf(app.repository.getSessionRule()) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Session intelligence", style = MaterialTheme.typography.headlineSmall)
        Text("Choose when a protected app must authenticate again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        SessionRule.entries.forEach { option -> RadioButtonRow(option, option == rule) { rule = option; app.repository.setSessionRule(option) } }
    }
}
@Composable private fun RadioButtonRow(option: SessionRule, selected: Boolean, onClick: () -> Unit) { Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(option.name.replace('_',' ')); RadioButton(selected, onClick) } }
