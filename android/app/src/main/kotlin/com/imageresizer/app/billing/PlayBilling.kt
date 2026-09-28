package com.imageresizer.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.imageresizer.app.BuildConfig

/**
 * Play Billing for the $5 lifetime unlock. The entitlement check is purely local
 * (`queryPurchasesAsync` — purchases live on-device; no server verification exists
 * by design, docs/ALGORITHM.md).
 */
class PlayBilling(private val context: Context) : BillingService {

    private var client: BillingClient? = null
    private var listener: BillingService.Listener? = null
    private var connecting = false

    override fun setListener(listener: BillingService.Listener?) {
        this.listener = listener
    }

    override fun connect() {
        if (client != null) {
            queryEntitlement()
            return
        }
        val billingClient = BillingClient.newBuilder(context)
            .setListener { result, purchases ->
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK ->
                        handlePurchases(purchases ?: emptyList())

                    BillingClient.BillingResponseCode.USER_CANCELED -> Unit

                    else -> listener?.onBillingUnavailable(result.debugMessage)
                }
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()
        client = billingClient
        startConnection(billingClient)
    }

    private fun startConnection(billingClient: BillingClient) {
        if (connecting || billingClient.isReady) return
        connecting = true
        billingClient.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    connecting = false
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryEntitlement()
                    } else {
                        listener?.onBillingUnavailable(result.debugMessage)
                    }
                }

                override fun onBillingServiceDisconnected() {
                    connecting = false
                    // Next query/purchase attempt reconnects.
                }
            },
        )
    }

    override fun queryEntitlement() {
        val billingClient = client ?: run {
            connect()
            return
        }
        if (!billingClient.isReady) {
            startConnection(billingClient)
            return
        }
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases)
            } else {
                listener?.onBillingUnavailable(result.debugMessage)
            }
        }
    }

    override fun purchase(activity: Activity) {
        val billingClient = client ?: run {
            listener?.onBillingUnavailable("Billing is starting, try again")
            connect()
            return
        }
        if (!billingClient.isReady) {
            listener?.onBillingUnavailable("Billing is starting, try again")
            startConnection(billingClient)
            return
        }
        val wanted = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(BuildConfig.PRODUCT_LIFETIME)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(wanted)).build(),
        ) { result, queryResult ->
            val details: List<ProductDetails> = queryResult.productDetailsList
            val lifetime = details.firstOrNull { it.productId == BuildConfig.PRODUCT_LIFETIME }
            if (result.responseCode != BillingClient.BillingResponseCode.OK || lifetime == null) {
                listener?.onBillingUnavailable("Product unavailable: ${result.debugMessage}")
                return@queryProductDetailsAsync
            }
            billingClient.launchBillingFlow(
                activity,
                BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(lifetime)
                                .build(),
                        ),
                    )
                    .build(),
            )
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        val lifetime = purchases.filter {
            it.products.contains(BuildConfig.PRODUCT_LIFETIME) &&
                it.purchaseState == Purchase.PurchaseState.PURCHASED
        }
        // Unacknowledged purchases are auto-refunded after ~3 days.
        val billingClient = client
        for (purchase in lifetime) {
            if (!purchase.isAcknowledged && billingClient?.isReady == true) {
                billingClient.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder()
                        .setPurchaseToken(purchase.purchaseToken)
                        .build(),
                ) {
                    // Acknowledgement result does not change the local flag.
                }
            }
        }
        listener?.onEntitlementChanged(lifetime.isNotEmpty())
    }
}
