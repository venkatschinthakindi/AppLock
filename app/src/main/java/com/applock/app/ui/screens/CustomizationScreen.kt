package applock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.AppLockApplication
import applock.app.domain.*

@Composable
fun CustomizationScreen() {
    val app = LocalContext.current.applicationContext as AppLockApplication
    val theme by app.repository.theme.collectAsState()
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Central theme", style = MaterialTheme.typography.headlineSmall)
        ThemeMode.entries.forEach { mode -> FilterChip(selected = theme.mode == mode, onClick = { app.repository.updateTheme { it.copy(mode = mode) } }, label = { Text(mode.name) }) }
        Text("Animation", style = MaterialTheme.typography.titleMedium)
        AnimationStyle.entries.forEach { style -> FilterChip(selected = theme.animationStyle == style, onClick = { app.repository.updateTheme { it.copy(animationStyle = style) } }, label = { Text(style.name.replace('_',' ')) }) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Reduced motion"); Switch(theme.reducedMotion, { app.repository.updateTheme { it.copy(reducedMotion = it.reducedMotion.not()) } }) }
        Text("Free defaults are fast and clean. Pro can unlock additional visual styles without changing security behavior.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
