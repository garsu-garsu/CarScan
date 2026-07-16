package com.bruni.carscan.core.monetization

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultEntitlementsTest {

    @Test
    fun `isPremium starts false with no cached purchases`() {
        val entitlements = DefaultEntitlements(
            clock = { 0L },
            cache = FakeEntitlementCache(),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        assertFalse(entitlements.isPremium.value)
    }

    @Test
    fun `pushing a lifetime purchase flips isPremium to true`() = runTest {
        val entitlements = DefaultEntitlements(clock = { 0L }, cache = FakeEntitlementCache(), scope = this)

        entitlements.isPremium.test {
            assertFalse(awaitItem())
            entitlements.updatePurchases(listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE)))
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pushing a refund after a lifetime purchase flips isPremium back to false`() = runTest {
        val entitlements = DefaultEntitlements(clock = { 0L }, cache = FakeEntitlementCache(), scope = this)
        entitlements.updatePurchases(listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE)))

        entitlements.isPremium.test {
            assertTrue(awaitItem())
            entitlements.updatePurchases(listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.REFUNDED)))
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Offline entitlement cache ---------------------------------------------------
    //
    // "엔타이틀먼트는 로컬 캐시 + 재검증. 오프라인에서 프리미엄이 꺼지면 안 된다." A lifetime
    // buyer must read as premium on a cold start with no `BillingPort` query having answered
    // yet — that is the whole point of caching purchase records rather than re-deriving
    // entitlement from a live call every time.

    @Test
    fun `isPremium starts true when a LIFETIME purchase was cached`() {
        val cache = FakeEntitlementCache(seed = listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE)))

        // An unconfined dispatcher runs the cache load in `init` to completion before the
        // constructor returns, so there is no false-then-true flicker to assert around: by
        // the time this instance exists, it already reflects the cached purchase.
        val entitlements = DefaultEntitlements(
            clock = { 0L },
            cache = cache,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(entitlements.isPremium.value)
    }

    @Test
    fun `updatePurchases persists the new purchases to the cache`() = runTest {
        val cache = FakeEntitlementCache()
        val entitlements = DefaultEntitlements(clock = { 0L }, cache = cache, scope = this)

        entitlements.updatePurchases(listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE)))
        advanceUntilIdle()

        assertEquals(1, cache.saveCallCount)
        assertEquals(listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE)), cache.load())
    }

    @Test
    fun `a cached lifetime purchase survives a simulated app restart with no store query`() = runTest {
        val cache = FakeEntitlementCache()
        val beforeRestart = DefaultEntitlements(clock = { 0L }, cache = cache, scope = this)
        beforeRestart.updatePurchases(listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE)))
        advanceUntilIdle()

        // Stands in for a cold start: a brand new instance, never fed a purchase by
        // `beforeRestart` or by a fresh `BillingPort.queryPurchases()` call — only what the
        // cache remembers from the run above.
        val afterRestart = DefaultEntitlements(
            clock = { 0L },
            cache = cache,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertTrue(afterRestart.isPremium.value)
    }
}
