package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.monetization.BillingPort
import com.bruni.carscan.core.monetization.Entitlements
import com.bruni.carscan.core.monetization.PurchaseKind
import kotlinx.coroutines.launch

/**
 * The paywall. [billing] launches a purchase or a restore; entitlement itself is never read back
 * from either call directly — see [BillingPort]'s KDoc on `onPurchasesChanged` — this screen
 * only ever observes [Entitlements.isPremium], which the real implementation keeps in sync.
 */
class PaywallViewModel(
    private val billing: BillingPort,
    entitlements: Entitlements,
) : MviViewModel<PaywallState, PaywallIntent, PaywallEffect>(PaywallState()) {

    init {
        entitlements.isPremium.collectIntoState { premium -> setState { copy(isPremium = premium) } }
    }

    override fun onIntent(intent: PaywallIntent) = when (intent) {
        is PaywallIntent.Purchase -> purchase(intent.kind)
        PaywallIntent.Restore -> restore()
        PaywallIntent.Close -> emitEffect(PaywallEffect.Close)
    }

    private fun purchase(kind: PurchaseKind) {
        setState { copy(purchasing = kind) }
        scope.launch {
            billing.purchase(kind)
            setState { copy(purchasing = null) }
        }
    }

    private fun restore() {
        scope.launch { billing.restore() }
    }
}
