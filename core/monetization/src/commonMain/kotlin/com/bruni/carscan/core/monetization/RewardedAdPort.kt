package com.bruni.carscan.core.monetization

/**
 * Platform port to the store's rewarded-ad SDK. Rewarded ads grant SESSION-ONLY
 * unlocks and must NEVER unlock the 30-day trip-history lock.
 */
interface RewardedAdPort {
    suspend fun showRewardedAd(): Boolean
}
