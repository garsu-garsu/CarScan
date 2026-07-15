package com.bruni.carscan.core.monetization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntitlementResolverTest {

    @Test
    fun `a lifetime purchase that has not been refunded grants premium`() {
        val purchases = listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE))
        val entitlement = EntitlementResolver.resolve(purchases, nowEpochMs = 0L)
        assertTrue(entitlement.isPremium)
        assertEquals(PurchaseKind.LIFETIME, entitlement.source)
    }

    @Test
    fun `a refunded lifetime purchase does not grant premium`() {
        val purchases = listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.REFUNDED))
        val entitlement = EntitlementResolver.resolve(purchases, nowEpochMs = 0L)
        assertFalse(entitlement.isPremium)
        assertNull(entitlement.source)
    }

    @Test
    fun `an active subscription grants premium`() {
        val purchases = listOf(Purchase(PurchaseKind.SUB_MONTHLY, PurchaseState.ACTIVE))
        assertTrue(EntitlementResolver.resolve(purchases, nowEpochMs = 0L).isPremium)
    }

    @Test
    fun `a subscription in its billing grace period still grants premium`() {
        val purchases = listOf(Purchase(PurchaseKind.SUB_YEARLY, PurchaseState.IN_GRACE_PERIOD))
        assertTrue(EntitlementResolver.resolve(purchases, nowEpochMs = 0L).isPremium)
    }

    @Test
    fun `a subscription on account hold does not grant premium`() {
        val purchases = listOf(Purchase(PurchaseKind.SUB_MONTHLY, PurchaseState.ON_ACCOUNT_HOLD))
        assertFalse(EntitlementResolver.resolve(purchases, nowEpochMs = 0L).isPremium)
    }

    @Test
    fun `an expired subscription does not grant premium`() {
        val purchases = listOf(Purchase(PurchaseKind.SUB_MONTHLY, PurchaseState.EXPIRED))
        assertFalse(EntitlementResolver.resolve(purchases, nowEpochMs = 0L).isPremium)
    }

    @Test
    fun `a lifetime purchase held alongside an expired subscription still grants premium, sourced to the lifetime`() {
        val purchases = listOf(
            Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE),
            Purchase(PurchaseKind.SUB_MONTHLY, PurchaseState.EXPIRED),
        )
        val entitlement = EntitlementResolver.resolve(purchases, nowEpochMs = 0L)
        assertTrue(entitlement.isPremium)
        assertEquals(PurchaseKind.LIFETIME, entitlement.source)
    }

    /**
     * The user is parked in a basement garage with no signal: entitlement must be
     * derivable from whatever purchase records were last cached, with no network call
     * and no dependence on what time it happens to be.
     */
    @Test
    fun `resolving is a pure function of its inputs, unaffected by the wall clock`() {
        val purchases = listOf(Purchase(PurchaseKind.LIFETIME, PurchaseState.ACTIVE))
        val atEpochZero = EntitlementResolver.resolve(purchases, nowEpochMs = 0L)
        val atFarFuture = EntitlementResolver.resolve(purchases, nowEpochMs = Long.MAX_VALUE)
        assertEquals(atEpochZero, atFarFuture)
        assertTrue(atFarFuture.isPremium)
    }

    @Test
    fun `holding no purchases grants no premium`() {
        val entitlement = EntitlementResolver.resolve(emptyList(), nowEpochMs = 0L)
        assertFalse(entitlement.isPremium)
        assertNull(entitlement.source)
    }
}
