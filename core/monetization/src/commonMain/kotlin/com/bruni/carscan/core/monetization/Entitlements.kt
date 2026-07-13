package com.bruni.carscan.core.monetization

import kotlinx.coroutines.flow.StateFlow

/**
 * Interfaces only. AdMob and Play Billing live in :platform:android-ads and
 * never reach the iOS klib path.
 */
interface Entitlements {
    val isPremium: StateFlow<Boolean>
}
