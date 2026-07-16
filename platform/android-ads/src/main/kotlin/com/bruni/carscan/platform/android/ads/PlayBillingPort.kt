package com.bruni.carscan.platform.android.ads

import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.bruni.carscan.core.monetization.BillingPort
import com.bruni.carscan.core.monetization.Purchase
import com.bruni.carscan.core.monetization.PurchaseKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import com.android.billingclient.api.Purchase as GooglePurchase

/**
 * Real Play Billing wiring. Device-tested only — `BillingClient` talks to the Play Store, which
 * does not exist on a JVM unit test host. [toDomainPurchase] carries the one piece of this that
 * is pure enough to unit-test; everything else here is exercised by hand on a device against a
 * license-tester account.
 *
 * [context] is only used to build the [BillingClient] and works with an application context.
 * [purchase] separately needs a foreground [android.app.Activity] to attach the Play purchase
 * sheet to, which it reads from [CurrentActivity] rather than [context] — this port is a Koin
 * singleton scoped to the whole app, not to one screen.
 *
 * [onPurchasesChanged] fires with the **complete** current purchase list — every INAPP and SUBS
 * record, not just whatever one call happened to touch — after [queryPurchases]/[restore] and
 * after the purchase sheet finishes. Wired in `PlatformModule.android.kt` straight to
 * [com.bruni.carscan.core.monetization.DefaultEntitlements.updatePurchases], so neither the
 * paywall nor the restore button has to know how entitlement gets re-resolved; they just call
 * [purchase] or [restore] and let this port keep [DefaultEntitlements][com.bruni.carscan.core.monetization.DefaultEntitlements] in sync.
 */
class PlayBillingPort(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPurchasesChanged: (List<Purchase>) -> Unit = {},
) : BillingPort {

    /** Completed by [listener] once the purchase sheet [purchase] launched resolves. */
    private var pendingPurchase: CompletableDeferred<List<Purchase>>? = null

    private val listener = PurchasesUpdatedListener { billingResult, purchases ->
        val result = if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.also { list -> list.forEach { acknowledgeIfNeeded(it) } }.flatMap { it.toDomainPurchases() }
        } else {
            emptyList()
        }
        pendingPurchase?.complete(result)
        pendingPurchase = null

        // A full re-query rather than handing `result` straight to `onPurchasesChanged`: `result`
        // is only the transaction this listener call is about, and forwarding just that would
        // drop, say, an existing lifetime purchase from the entitlement DefaultEntitlements holds.
        // queryPurchases() below calls onPurchasesChanged itself once it has the complete list.
        scope.launch { queryPurchases() }
    }

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(listener)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private suspend fun ensureConnected() {
        if (client.isReady) return
        suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                }

                override fun onBillingServiceDisconnected() {
                    // The next call that needs the client calls ensureConnected() again.
                }
            })
        }
    }

    override suspend fun queryPurchases(): List<Purchase> {
        ensureConnected()
        val result = queryPurchasesOfType(BillingClient.ProductType.INAPP) +
            queryPurchasesOfType(BillingClient.ProductType.SUBS)
        onPurchasesChanged(result)
        return result
    }

    override suspend fun restore(): List<Purchase> = queryPurchases()

    override suspend fun queryPrices(): Map<PurchaseKind, String> = runCatching {
        ensureConnected()
        val prices = mutableMapOf<PurchaseKind, String>()

        val lifetimeDetails = queryProductDetails(PurchaseKind.LIFETIME.toProductId(), BillingClient.ProductType.INAPP)
        lifetimeDetails?.oneTimePurchaseOfferDetails?.formattedPrice?.let { prices[PurchaseKind.LIFETIME] = it }

        for (kind in listOf(PurchaseKind.SUB_MONTHLY, PurchaseKind.SUB_YEARLY)) {
            val details = queryProductDetails(kind.toProductId(), BillingClient.ProductType.SUBS)
            val price = details?.subscriptionOfferDetails?.firstOrNull()?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
            price?.let { prices[kind] = it }
        }

        prices
    }.getOrDefault(emptyMap())

    override suspend fun purchase(kind: PurchaseKind): List<Purchase> {
        ensureConnected()
        val activity = CurrentActivity.value
            ?: error("PlayBillingPort.purchase() needs a resumed Activity to launch the purchase sheet.")

        val productType = if (kind == PurchaseKind.LIFETIME) {
            BillingClient.ProductType.INAPP
        } else {
            BillingClient.ProductType.SUBS
        }

        val productDetails = queryProductDetails(kind.toProductId(), productType) ?: return emptyList()

        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .apply { offerToken?.let { setOfferToken(it) } }
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        val deferred = CompletableDeferred<List<Purchase>>()
        pendingPurchase = deferred

        val launchResult = client.launchBillingFlow(activity, flowParams)
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            pendingPurchase = null
            return emptyList()
        }

        // Resolved by `listener` (PurchasesUpdatedListener) once the user finishes the sheet.
        return deferred.await()
    }

    private suspend fun queryPurchasesOfType(productType: String): List<Purchase> =
        suspendCancellableCoroutine { continuation ->
            val params = QueryPurchasesParams.newBuilder().setProductType(productType).build()
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                if (!continuation.isActive) return@queryPurchasesAsync
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchases.forEach { acknowledgeIfNeeded(it) }
                    continuation.resumeWith(Result.success(purchases.flatMap { it.toDomainPurchases() }))
                } else {
                    continuation.resumeWith(Result.success(emptyList()))
                }
            }
        }

    private suspend fun queryProductDetails(productId: String, productType: String): ProductDetails? =
        suspendCancellableCoroutine { continuation ->
            val params = QueryProductDetailsParams.newBuilder()
                .setProductList(
                    listOf(
                        QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(productId)
                            .setProductType(productType)
                            .build(),
                    ),
                )
                .build()
            client.queryProductDetailsAsync(params) { _, productDetailsList ->
                if (continuation.isActive) {
                    continuation.resumeWith(Result.success(productDetailsList.firstOrNull()))
                }
            }
        }

    /**
     * Google refunds an un-acknowledged purchase automatically after three days. Fired and
     * forgotten on [scope]: acknowledgment does not gate whether this purchase already counts as
     * entitled, only whether Google eventually reverses it for staying silent too long.
     */
    private fun acknowledgeIfNeeded(purchase: GooglePurchase) {
        if (purchase.purchaseState != GooglePurchaseState.PURCHASED || purchase.isAcknowledged) return
        scope.launch {
            suspendCancellableCoroutine { continuation ->
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                client.acknowledgePurchase(params) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
                }
            }
        }
    }

    private fun GooglePurchase.toDomainPurchases(): List<Purchase> =
        products.mapNotNull { productId ->
            toDomainPurchase(productId, purchaseState, expiryEpochMs = null)
        }
}
