package com.bruni.carscan.core.monetization

/** What was bought: a recurring subscription, or a one-time lifetime unlock. */
enum class PurchaseKind { SUB_MONTHLY, SUB_YEARLY, LIFETIME }

/**
 * Where a purchase currently stands with the store. Grace and hold are both
 * "payment failed" but differ in whether the user is still entitled while the
 * store retries the charge.
 */
enum class PurchaseState { ACTIVE, IN_GRACE_PERIOD, ON_ACCOUNT_HOLD, EXPIRED, REFUNDED }

/** A single cached purchase record, as last reported by the store. */
data class Purchase(
    val kind: PurchaseKind,
    val state: PurchaseState,
    val expiryEpochMs: Long? = null,
)

/** The resolved entitlement. [source] is the purchase kind that grants it, if any. */
data class Entitlement(val isPremium: Boolean, val source: PurchaseKind?)
