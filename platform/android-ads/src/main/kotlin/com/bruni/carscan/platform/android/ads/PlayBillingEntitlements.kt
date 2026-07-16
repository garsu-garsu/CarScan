package com.bruni.carscan.platform.android.ads

import com.bruni.carscan.core.monetization.BillingPort
import com.bruni.carscan.core.monetization.DefaultEntitlements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The startup trigger for [DefaultEntitlements]'s billing sync. [PlayBillingPort] is built with an
 * `onPurchasesChanged` callback (see its KDoc) that already feeds every query result into
 * [DefaultEntitlements.updatePurchases] — `refresh` just has to ask [billing] to query once, the
 * same way `CarScanApplication` starts `TripRecorder`: `get<PlayBillingEntitlements>().refresh()`.
 *
 * A lapsed subscription or a store-side refund is then picked up promptly at app startup, rather
 * than only the next time the user makes a purchase. `isPremium` itself needs none of this to be
 * correct on a cold start — see [DefaultEntitlements]'s KDoc on its offline cache.
 */
class PlayBillingEntitlements(
    private val billing: BillingPort,
    private val scope: CoroutineScope,
) {
    fun refresh() {
        scope.launch { billing.queryPurchases() }
    }
}
