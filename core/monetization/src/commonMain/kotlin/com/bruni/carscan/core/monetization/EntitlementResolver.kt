package com.bruni.carscan.core.monetization

/**
 * Pure resolution of premium status from cached purchase records. No IO, no
 * platform calls: this must be computable offline, e.g. in a basement parking
 * garage with no signal, from whatever was last synced.
 */
object EntitlementResolver {

    /**
     * A held, non-refunded lifetime purchase always wins: it is never revoked by a
     * lapsed subscription, and [nowEpochMs] cannot affect it. Otherwise, premium
     * comes from a subscription that is ACTIVE or IN_GRACE_PERIOD.
     */
    fun resolve(purchases: List<Purchase>, nowEpochMs: Long): Entitlement {
        val lifetime = purchases.firstOrNull { it.kind == PurchaseKind.LIFETIME && it.state != PurchaseState.REFUNDED }
        if (lifetime != null) {
            return Entitlement(isPremium = true, source = PurchaseKind.LIFETIME)
        }

        val activeSub = purchases.firstOrNull {
            it.kind != PurchaseKind.LIFETIME &&
                (it.state == PurchaseState.ACTIVE || it.state == PurchaseState.IN_GRACE_PERIOD)
        }
        if (activeSub != null) {
            return Entitlement(isPremium = true, source = activeSub.kind)
        }

        return Entitlement(isPremium = false, source = null)
    }
}
