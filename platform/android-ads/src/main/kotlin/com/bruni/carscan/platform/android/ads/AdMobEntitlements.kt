package com.bruni.carscan.platform.android.ads

import com.bruni.carscan.core.monetization.Entitlements
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Placeholder. Play Billing lands in M6. */
class AdMobEntitlements : Entitlements {
    override val isPremium: StateFlow<Boolean> = MutableStateFlow(false)
}
