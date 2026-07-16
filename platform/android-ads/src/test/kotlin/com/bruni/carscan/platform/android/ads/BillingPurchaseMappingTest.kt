package com.bruni.carscan.platform.android.ads

import com.bruni.carscan.core.monetization.PurchaseKind
import com.bruni.carscan.core.monetization.PurchaseState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [toDomainPurchase] is the one piece of [PlayBillingPort] pure enough to unit-test on a plain
 * JVM: it takes the product id string and the raw `purchaseState` int Google's `Purchase`
 * exposes, not the SDK type itself, so no Android SDK / Robolectric is needed here. The rest of
 * [PlayBillingPort] — BillingClient connection, the purchase flow, the listener — talks to the
 * Play Store and is device-tested only.
 */
class BillingPurchaseMappingTest {

    @Test
    fun `a purchased lifetime product id maps to an ACTIVE LIFETIME purchase`() {
        val purchase = toDomainPurchase(
            productId = PRODUCT_ID_LIFETIME,
            billingPurchaseState = GooglePurchaseState.PURCHASED,
            expiryEpochMs = null,
        )
        assertEquals(PurchaseKind.LIFETIME, purchase?.kind)
        assertEquals(PurchaseState.ACTIVE, purchase?.state)
    }

    @Test
    fun `a purchased monthly subscription product id maps to SUB_MONTHLY`() {
        val purchase = toDomainPurchase(
            productId = PRODUCT_ID_SUB_MONTHLY,
            billingPurchaseState = GooglePurchaseState.PURCHASED,
            expiryEpochMs = 1_700_000_000_000,
        )
        assertEquals(PurchaseKind.SUB_MONTHLY, purchase?.kind)
        assertEquals(PurchaseState.ACTIVE, purchase?.state)
        assertEquals(1_700_000_000_000, purchase?.expiryEpochMs)
    }

    @Test
    fun `a purchased yearly subscription product id maps to SUB_YEARLY`() {
        val purchase = toDomainPurchase(
            productId = PRODUCT_ID_SUB_YEARLY,
            billingPurchaseState = GooglePurchaseState.PURCHASED,
            expiryEpochMs = null,
        )
        assertEquals(PurchaseKind.SUB_YEARLY, purchase?.kind)
    }

    @Test
    fun `a PENDING purchase state maps to EXPIRED — not yet entitled`() {
        // Google's client SDK has no client-visible grace/hold/refund state; those come from the
        // Play Developer API / RTDN server-side, out of scope for this client-only wiring. PENDING
        // (e.g. a cash payment method awaiting completion) has not granted the purchase yet, so it
        // must not resolve to ACTIVE — EXPIRED is the closest "not entitled" domain state.
        val purchase = toDomainPurchase(
            productId = PRODUCT_ID_LIFETIME,
            billingPurchaseState = GooglePurchaseState.PENDING,
            expiryEpochMs = null,
        )
        assertEquals(PurchaseState.EXPIRED, purchase?.state)
    }

    @Test
    fun `an unrecognised product id maps to no purchase at all`() {
        val purchase = toDomainPurchase(
            productId = "some_other_apps_product",
            billingPurchaseState = GooglePurchaseState.PURCHASED,
            expiryEpochMs = null,
        )
        assertNull(purchase)
    }
}
