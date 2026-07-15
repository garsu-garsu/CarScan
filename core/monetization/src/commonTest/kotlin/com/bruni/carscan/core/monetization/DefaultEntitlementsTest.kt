package com.bruni.carscan.core.monetization

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultEntitlementsTest {

    @Test
    fun `isPremium starts false with no cached purchases`() {
        val entitlements = DefaultEntitlements(clock = { 0L })
        assertFalse(entitlements.isPremium.value)
    }

    @Test
    fun `pushing a lifetime purchase flips isPremium to true`() = runTest {
        val entitlements = DefaultEntitlements(clock = { 0L })

        entitlements.isPremium.test {
            assertFalse(awaitItem())
            entitlements.updatePurchases(listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE)))
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pushing a refund after a lifetime purchase flips isPremium back to false`() = runTest {
        val entitlements = DefaultEntitlements(clock = { 0L })
        entitlements.updatePurchases(listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE)))

        entitlements.isPremium.test {
            assertTrue(awaitItem())
            entitlements.updatePurchases(listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.REFUNDED)))
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
