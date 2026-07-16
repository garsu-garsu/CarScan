package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.monetization.PurchaseKind

data class PaywallState(
    val isPremium: Boolean = false,
    /** The tier currently in flight, so its button can show progress instead of every button at once. */
    val purchasing: PurchaseKind? = null,
    /**
     * The store's localized, currency-formatted price per tier (e.g. "$9.99", "₩13,000"), loaded
     * once when the paywall opens. A tier is absent when its product is unavailable or billing
     * could not connect (offline) — the screen then falls back to its static placeholder price.
     */
    val prices: Map<PurchaseKind, String> = emptyMap(),
)

sealed interface PaywallIntent {
    data class Purchase(val kind: PurchaseKind) : PaywallIntent
    data object Restore : PaywallIntent
    data object Close : PaywallIntent
}

sealed interface PaywallEffect {
    data object Close : PaywallEffect
}
