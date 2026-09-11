package applock.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BillingManager(context: Context) : PurchasesUpdatedListener {
    companion object { const val PRODUCT_ID = "app_lock_pro_monthly" }
    private val _pro = MutableStateFlow(false)
    val isPro: StateFlow<Boolean> = _pro.asStateFlow()
    private val client = BillingClient.newBuilder(context).setListener(this).enablePendingPurchases().build()

    fun connect(onReady: () -> Unit = {}) {
        if (client.isReady) { onReady(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) { if (result.responseCode == BillingClient.BillingResponseCode.OK) { refreshEntitlement(); onReady() } }
            override fun onBillingServiceDisconnected() = Unit
        })
    }
    fun refreshEntitlement() {
        if (!client.isReady) return
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.filter { it.products.contains(PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED }.forEach { purchase ->
                    if (!purchase.isAcknowledged) client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()) { }
                }
                _pro.value = purchases.any { it.products.contains(PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
            }
        }
    }
    fun launchPurchase(activity: Activity) {
        if (!client.isReady) { connect { launchPurchase(activity) }; return }
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.SUBS).build())).build()) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val product = details.firstOrNull() ?: return@queryProductDetailsAsync
            val offer = product.subscriptionOfferDetails?.firstOrNull() ?: return@queryProductDetailsAsync
            val params = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(product).setOfferToken(offer.offerToken).build()
            client.launchBillingFlow(activity, BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build())
        }
    }
    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) refreshEntitlement()
    }
}
