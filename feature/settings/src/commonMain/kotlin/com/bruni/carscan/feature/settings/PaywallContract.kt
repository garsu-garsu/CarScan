package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.monetization.PurchaseKind

data class PaywallState(
    val isPremium: Boolean = false,
    /** The tier currently in flight, so its button can show progress instead of every button at once. */
    val purchasing: PurchaseKind? = null,
)

sealed interface PaywallIntent {
    data class Purchase(val kind: PurchaseKind) : PaywallIntent
    data object Restore : PaywallIntent
    data object Close : PaywallIntent
}

sealed interface PaywallEffect {
    data object Close : PaywallEffect
}
