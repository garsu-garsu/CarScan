package com.bruni.carscan.core.monetization

/** In-memory [EntitlementCache]. [seed] stands in for whatever a previous run persisted. */
class FakeEntitlementCache(seed: List<Purchase> = emptyList()) : EntitlementCache {

    private var stored: List<Purchase> = seed
    var saveCallCount: Int = 0
        private set

    override suspend fun load(): List<Purchase> = stored

    override suspend fun save(purchases: List<Purchase>) {
        stored = purchases
        saveCallCount++
    }
}
