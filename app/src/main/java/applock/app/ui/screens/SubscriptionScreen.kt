package applock.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.billing.BillingManager

@Composable
fun SubscriptionScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val billing = remember { BillingManager(context.applicationContext) }
    val pro by billing.isPro.collectAsState()
    LaunchedEffect(Unit) { billing.connect() }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("App Lock Pro", style = MaterialTheme.typography.headlineMedium)
        if (pro) AssistChip(onClick = {}, label = { Text("Pro active") })
        Text("₹30/month initially. Google Play displays the final price, billing period, renewal and cancellation terms before purchase.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Includes", style = MaterialTheme.typography.titleMedium)
        Text("• No ads in eligible app surfaces\n• Premium themes and unlock animations\n• Advanced customization\n• Future convenience features")
        Button(
    enabled = activity != null && !pro,
    onClick = {
        activity?.let { currentActivity ->
            billing.launchPurchase(currentActivity)
        }
    },
    modifier = Modifier.fillMaxWidth()
) {
    Text(
        if (pro) {
            "Subscription active"
        } else {
            "Continue with Google Play"
        }
    )
}
    }
}
