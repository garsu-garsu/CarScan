package com.bruni.carscan.core.monetization

/** Platform port to the store's billing SDK. Implemented in :platform:android-ads. */
interface BillingPort {
    suspend fun queryPurchases(): List<Purchase>
    suspend fun purchase(kind: PurchaseKind): List<Purchase>
    suspend fun restore(): List<Purchase>
}
