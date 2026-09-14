package applock.app.ui.screens

import android.app.Activity
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ProHeader(pro = pro)

        ProStatusCard(pro = pro)

        ProBenefitsCard()

        BillingInformationCard()

        PurchaseCard(
            pro = pro,
            purchaseEnabled = activity != null && !pro,
            onPurchase = {
                activity?.let(billing::launchPurchase)
            }
        )
    }
}

@Composable
private fun ProHeader(pro: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.WorkspacePremium,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "App Lock Pro",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = if (pro) {
                        "Premium features are active."
                    } else {
                        "Unlock the premium AppLock experience."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProStatusCard(pro: Boolean) {
    if (!pro) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Pro active",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Text(
                    text = "Your premium AppLock features are enabled.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun ProBenefitsCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "What Pro includes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            ProBenefit(
                text = "No ads in eligible app surfaces"
            )

            ProBenefit(
                text = "Premium themes and unlock animations"
            )

            ProBenefit(
                text = "Advanced customization"
            )

            ProBenefit(
                text = "Future convenience features"
            )
        }
    }
}

@Composable
private fun ProBenefit(
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun BillingInformationCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "Google Play billing",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = "₹30/month initially. Google Play displays the final price, billing period, renewal and cancellation terms before purchase.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PurchaseCard(
    pro: Boolean,
    purchaseEnabled: Boolean,
    onPurchase: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = if (pro) {
                    "Your subscription is active"
                } else {
                    "Ready to upgrade?"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = if (pro) {
                    "Premium features are currently available on this account."
                } else {
                    "Continue through Google Play to review the current subscription details and complete the purchase."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (pro) {
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null
                    )

                    Text(
                        text = "Subscription active",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            } else {
                Button(
                    enabled = purchaseEnabled,
                    onClick = onPurchase,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.WorkspacePremium,
                        contentDescription = null
                    )

                    Text(
                        text = "Continue with Google Play",
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}