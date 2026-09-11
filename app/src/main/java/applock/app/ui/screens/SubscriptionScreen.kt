package applock.app.ui.screens

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import applock.app.billing.BillingManager

@Composable
fun SubscriptionScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val billing = remember {
        BillingManager(context.applicationContext)
    }

    val pro by billing.isPro.collectAsState()

    LaunchedEffect(Unit) {
        billing.connect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "App Lock Pro",
            style = MaterialTheme.typography.headlineMedium
        )

        if (pro) {
            AssistChip(
                onClick = {},
                label = {
                    Text("Pro active")
                }
            )
        }

        Text(
            text = "₹30/month initially. Google Play displays the final price, " +
                "billing period, renewal and cancellation terms before purchase.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = "Includes",
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = "• No ads in eligible app surfaces\n" +
                "• Premium themes and unlock animations\n" +
                "• Advanced customization\n" +
                "• Future convenience features"
        )

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
