package com.bruni.carscan.core.monetization

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Default [Entitlements] backed by a cache of purchase records and an injected
 * clock, resolved through [EntitlementResolver]. The platform layer pushes fresh
 * purchase records in via [updatePurchases] whenever it hears from the store;
 * between pushes, [isPremium] reflects the last cached state with no IO.
 *
 * [cache] is [EntitlementCache]'s other half: on [init], the last-known purchase records are
 * loaded from disk and resolved into [isPremium] before [BillingPort] has answered a single
 * query — the requirement is that premium survives a restart with no network, not just a
 * dropped connection while the app stays open. Every [updatePurchases] call persists back to
 * it, so the next cold start seeds from what this one learned.
 *
 * The load is asynchronous (a real cache is disk or DataStore IO), so [isPremium] starts from
 * an empty purchase list — `false` — and flips to the cached answer once [scope] has run the
 * load. Callers that need the seeded value with no observable flicker (see
 * `DefaultEntitlementsTest`) can supply a [scope] backed by an unconfined dispatcher, which
 * runs that load synchronously within [init].
 */
class DefaultEntitlements(
    private val clock: () -> Long,
    private val cache: EntitlementCache,
    private val scope: CoroutineScope,
) : Entitlements {

    private val purchases = MutableStateFlow<List<Purchase>>(emptyList())

    private val mutableIsPremium = MutableStateFlow(resolve())
    override val isPremium: StateFlow<Boolean> = mutableIsPremium.asStateFlow()

    init {
        scope.launch {
            val cached = cache.load()
            if (cached.isNotEmpty()) {
                purchases.value = cached
                mutableIsPremium.value = resolve()
            }
        }
    }

    fun updatePurchases(newPurchases: List<Purchase>) {
        purchases.value = newPurchases
        mutableIsPremium.value = resolve()
        scope.launch { cache.save(newPurchases) }
    }

    private fun resolve(): Boolean = EntitlementResolver.resolve(purchases.value, clock()).isPremium
}
