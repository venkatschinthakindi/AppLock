package applock.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun OnboardingScreen(onContinue: () -> Unit) { Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) { Text("Fast protection, designed around privacy.", style = MaterialTheme.typography.headlineLarge); Spacer(Modifier.height(12.dp)); Text("App Lock uses a narrow Accessibility Service to detect protected app launches. It does not need private screen content for this purpose."); Spacer(Modifier.height(24.dp)); Button(onClick = onContinue, Modifier.fillMaxWidth()) { Text("Continue") } } }
