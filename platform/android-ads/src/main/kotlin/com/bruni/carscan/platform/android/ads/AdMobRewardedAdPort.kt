package com.bruni.carscan.platform.android.ads

import android.content.Context
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.bruni.carscan.core.monetization.RewardedAdPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Real AdMob rewarded ad. Device-tested only, same reason as [PlayBillingPort]: there is no Play
 * Store or ad inventory on a JVM unit test host.
 *
 * [BuildConfig.REWARDED_AD_UNIT_ID] is Google's public TEST unit id by default — see this
 * module's `build.gradle.kts` — overridable per machine via the gitignored `local.properties`
 * key `admob.rewarded.adunit`.
 *
 * Grants nothing itself: [showRewardedAd] resolves `true` only from the earned-reward callback,
 * and the caller decides what a session-only unlock means. It must never be wired to the 30-day
 * trip-history lock — see [RewardedAdPort]'s KDoc.
 *
 * [context] only builds the ad request; showing one needs a foreground Activity, read from
 * [CurrentActivity] for the same reason [PlayBillingPort] does — this port is a Koin singleton
 * scoped to the whole app, not to one screen.
 */
class AdMobRewardedAdPort(private val context: Context) : RewardedAdPort {

    init {
        MobileAds.initialize(context)
    }

    override suspend fun showRewardedAd(): Boolean {
        val activity = CurrentActivity.value ?: return false

        val ad = loadAd() ?: return false

        val earnedReward = CompletableDeferred<Boolean>()
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                earnedReward.complete(false)
            }

            override fun onAdDismissedFullScreenContent() {
                if (!earnedReward.isCompleted) earnedReward.complete(false)
            }
        }

        ad.show(activity) { earnedReward.complete(true) }

        return earnedReward.await()
    }

    private suspend fun loadAd(): RewardedAd? = suspendCancellableCoroutine { continuation ->
        RewardedAd.load(
            context,
            BuildConfig.REWARDED_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(rewardedAd: RewardedAd) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(rewardedAd))
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }
            },
        )
    }
}
