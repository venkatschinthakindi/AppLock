package applock.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import applock.app.domain.SessionRule

@Composable
fun SmartLockScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    var rule by remember { mutableStateOf(app.repository.getSessionRule()) }
    val options = listOf(
        Triple(SessionRule.IMMEDIATELY, "Every launch", "Authenticate whenever a protected app opens."),
        Triple(SessionRule.AFTER_LEAVING, "After leaving", "Authenticate again after leaving the protected app."),
        Triple(SessionRule.SCREEN_OFF, "After screen off", "Treat screen-off as the end of the unlock session."),
        Triple(SessionRule.MINUTES_1, "1 minute", "Keep the session for one minute."),
        Triple(SessionRule.MINUTES_5, "5 minutes", "Keep the session for five minutes."),
        Triple(SessionRule.MINUTES_15, "15 minutes", "Keep the session for fifteen minutes."),
        Triple(SessionRule.MINUTES_30, "30 minutes", "Keep the session for thirty minutes.")
    )
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Session intelligence", style = MaterialTheme.typography.headlineSmall)
        Text("Choose how often AppLock should require authentication.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        options.forEach { item ->
            val ruleItem = item.first
            val title = item.second
            val detail = item.third
            Card(
                Modifier.fillMaxWidth().clickable { rule = ruleItem; app.repository.setSessionRule(ruleItem) }
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    RadioButton(selected = rule == ruleItem, onClick = { rule = ruleItem; app.repository.setSessionRule(ruleItem) })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
