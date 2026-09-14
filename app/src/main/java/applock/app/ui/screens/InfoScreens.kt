package applock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun AboutScreen() { Column(Modifier.fillMaxSize().padding(20.dp)) { Text("AppLock – Private App Locker", style = MaterialTheme.typography.headlineMedium); Text("Privacy-focused app protection with a native Kotlin lock engine."); Spacer(Modifier.height(16.dp)); Text("Version 1.0.0") } }
