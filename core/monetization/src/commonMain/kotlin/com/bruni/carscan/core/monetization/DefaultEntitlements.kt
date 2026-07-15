package com.bruni.carscan.core.monetization

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Default [Entitlements] backed by a cache of purchase records and an injected
 * clock, resolved through [EntitlementResolver]. The platform layer pushes fresh
 * purchase records in via [updatePurchases] whenever it hears from the store;
 * between pushes, [isPremium] reflects the last cached state with no IO.
 */
class DefaultEntitlements(private val clock: () -> Long) : Entitlements {

    private val purchases = MutableStateFlow<List<Purchase>>(emptyList())

    private val mutableIsPremium = MutableStateFlow(resolve())
    override val isPremium: StateFlow<Boolean> = mutableIsPremium.asStateFlow()

    fun updatePurchases(newPurchases: List<Purchase>) {
        purchases.value = newPurchases
        mutableIsPremium.value = resolve()
    }

    private fun resolve(): Boolean = EntitlementResolver.resolve(purchases.value, clock()).isPremium
}
