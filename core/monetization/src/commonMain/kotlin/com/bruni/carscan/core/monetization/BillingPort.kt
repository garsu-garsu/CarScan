package com.bruni.carscan.core.monetization

/** Platform port to the store's billing SDK. Implemented in :platform:android-ads. */
interface BillingPort {
    suspend fun queryPurchases(): List<Purchase>
    suspend fun purchase(kind: PurchaseKind): List<Purchase>
    suspend fun restore(): List<Purchase>

    /**
     * The store's localized, currency-formatted price string per [PurchaseKind] (e.g. "$9.99",
     * "₩13,000"). A kind is absent from the map when its product is unavailable or unconfigured
     * in the store; returns an empty map if billing can't connect at all.
     */
    suspend fun queryPrices(): Map<PurchaseKind, String>
}
