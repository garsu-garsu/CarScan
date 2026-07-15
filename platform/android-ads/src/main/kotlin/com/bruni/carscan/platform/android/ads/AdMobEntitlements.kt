package com.bruni.carscan.platform.android.ads

import com.bruni.carscan.core.monetization.BillingPort
import com.bruni.carscan.core.monetization.DefaultEntitlements
import com.bruni.carscan.core.monetization.Entitlements
import com.bruni.carscan.core.monetization.Purchase
import com.bruni.carscan.core.monetization.PurchaseKind
import com.bruni.carscan.core.monetization.RewardedAdPort

/**
 * Placeholder. Wraps the real [DefaultEntitlements]/[com.bruni.carscan.core.monetization.EntitlementResolver]
 * but is fed no purchases yet, so it reports not-premium until Play Billing is wired in.
 */
class AdMobEntitlements : Entitlements {
    private val delegate = DefaultEntitlements(clock = { System.currentTimeMillis() })
    override val isPremium = delegate.isPremium
}

// TODO: real Play Billing / AdMob with real IDs (deferred — needs user's store accounts)
class StubBillingPort : BillingPort {
    override suspend fun queryPurchases(): List<Purchase> = emptyList()
    override suspend fun purchase(kind: PurchaseKind): List<Purchase> = emptyList()
    override suspend fun restore(): List<Purchase> = emptyList()
}

// TODO: real Play Billing / AdMob with real IDs (deferred — needs user's store accounts)
class StubRewardedAdPort : RewardedAdPort {
    override suspend fun showRewardedAd(): Boolean = false
}
