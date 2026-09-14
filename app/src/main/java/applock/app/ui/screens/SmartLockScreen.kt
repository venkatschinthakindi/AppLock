package applock.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import applock.app.AppLockApplication
import applock.app.domain.SessionRule

@Composable
fun SmartLockScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication

    if (app.repository.hasCredential()) {
        SecurityGateScreen(
            title = "Session rules are secured",
            description = "Authenticate before changing when protected apps can remain unlocked."
        ) {
            SmartLockEditor()
        }
    } else {
        SmartLockEditor()
    }
}

@Composable
private fun SmartLockEditor() {
    val app = LocalContext.current.applicationContext as AppLockApplication

    var rule by remember {
        mutableStateOf(app.repository.getSessionRule())
    }

    val options = listOf(
        Triple(
            SessionRule.IMMEDIATELY,
            "Every launch",
            "Authenticate whenever a protected app opens."
        ),
        Triple(
            SessionRule.AFTER_LEAVING,
            "After leaving",
            "Authenticate again after leaving the protected app."
        ),
        Triple(
            SessionRule.SCREEN_OFF,
            "After screen off",
            "Treat screen-off as the end of the unlock session."
        ),
        Triple(
            SessionRule.MINUTES_1,
            "1 minute",
            "Keep the session for one minute."
        ),
        Triple(
            SessionRule.MINUTES_5,
            "5 minutes",
            "Keep the session for five minutes."
        ),
        Triple(
            SessionRule.MINUTES_15,
            "15 minutes",
            "Keep the session for fifteen minutes."
        ),
        Triple(
            SessionRule.MINUTES_30,
            "30 minutes",
            "Keep the session for thirty minutes."
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = "Session intelligence",
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                text = "Choose how often AppLock should require authentication.",
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        options.forEach { item ->
            val ruleItem = item.first
            val title = item.second
            val detail = item.third
            val selected = rule == ruleItem

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        rule = ruleItem
                        app.repository.setSessionRule(ruleItem)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    }
                ),
                border = if (selected) {
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary
                    )
                } else {
                    null
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selected,
                        onClick = {
                            rule = ruleItem
                            app.repository.setSessionRule(ruleItem)
                        }
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )

                        Text(
                            text = detail,
                            modifier = Modifier.padding(top = 2.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}