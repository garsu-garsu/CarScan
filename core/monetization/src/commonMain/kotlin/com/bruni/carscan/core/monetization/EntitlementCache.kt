package com.bruni.carscan.core.monetization

/**
 * The offline half of entitlement resolution: the last purchase records the store confirmed,
 * persisted to disk. [DefaultEntitlements] seeds itself from this on startup so a lapsed network
 * connection — or no network at all — cannot turn premium off for someone who already paid for
 * it. It is re-populated every time [BillingPort] answers, not read from on every check.
 */
interface EntitlementCache {
    suspend fun load(): List<Purchase>
    suspend fun save(purchases: List<Purchase>)
}
