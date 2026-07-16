package com.bruni.carscan.platform.android.ads

import com.bruni.carscan.core.monetization.Purchase
import com.bruni.carscan.core.monetization.PurchaseKind
import com.bruni.carscan.core.monetization.PurchaseState

/** Play Store product ids. Must match the products created in Play Console exactly. */
const val PRODUCT_ID_LIFETIME = "carscan_lifetime"
const val PRODUCT_ID_SUB_MONTHLY = "carscan_sub_monthly"
const val PRODUCT_ID_SUB_YEARLY = "carscan_sub_yearly"

/**
 * Mirrors `com.android.billingclient.api.Purchase.PurchaseState`'s int constants. Duplicated
 * here — rather than imported from the SDK — so [toDomainPurchase] stays free of the Android
 * SDK and runs as a plain JVM unit test; [PlayBillingPort] passes the SDK's real `purchaseState`
 * int straight through, so the two cannot drift silently.
 */
object GooglePurchaseState {
    const val PURCHASED = 1
    const val PENDING = 2
}

/**
 * The one piece of [PlayBillingPort] pure enough to unit-test: given the product id and raw
 * `purchaseState` int off a Google `Purchase`, resolves the domain [Purchase] it represents.
 *
 * Google's client SDK has no client-visible grace period / account hold / refund state — those
 * come from the Play Developer API or Realtime Developer Notifications, server-side, which this
 * client-only wiring does not implement. [GooglePurchaseState.PENDING] (e.g. a cash payment
 * method awaiting completion) has not granted entitlement yet, so it maps to
 * [PurchaseState.EXPIRED] — the closest "not entitled" domain state — rather than [PurchaseState.ACTIVE].
 *
 * Returns `null` for a product id this app does not sell — e.g. from a different app sharing the
 * same Play Console account in a test environment.
 */
fun toDomainPurchase(productId: String, billingPurchaseState: Int, expiryEpochMs: Long?): Purchase? {
    val kind = when (productId) {
        PRODUCT_ID_LIFETIME -> PurchaseKind.LIFETIME
        PRODUCT_ID_SUB_MONTHLY -> PurchaseKind.SUB_MONTHLY
        PRODUCT_ID_SUB_YEARLY -> PurchaseKind.SUB_YEARLY
        else -> return null
    }
    val state = if (billingPurchaseState == GooglePurchaseState.PURCHASED) {
        PurchaseState.ACTIVE
    } else {
        PurchaseState.EXPIRED
    }
    return Purchase(kind, state, expiryEpochMs)
}

internal fun PurchaseKind.toProductId(): String = when (this) {
    PurchaseKind.LIFETIME -> PRODUCT_ID_LIFETIME
    PurchaseKind.SUB_MONTHLY -> PRODUCT_ID_SUB_MONTHLY
    PurchaseKind.SUB_YEARLY -> PRODUCT_ID_SUB_YEARLY
}
