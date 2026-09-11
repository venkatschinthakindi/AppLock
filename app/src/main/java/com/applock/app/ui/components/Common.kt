package applock.app.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 10.dp)) }
@Composable fun SettingRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = subtitle?.let { { Text(it) } }, modifier = Modifier, trailingContent = {})
}
