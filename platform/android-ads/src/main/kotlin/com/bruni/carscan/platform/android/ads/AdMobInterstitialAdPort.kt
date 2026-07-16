package com.bruni.carscan.platform.android.ads

import android.content.Context
import com.bruni.carscan.core.monetization.InterstitialAdPort
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.CompletableDeferred

/**
 * Real AdMob interstitial. Device-tested only, same reason as [AdMobRewardedAdPort]: there is
 * no ad inventory on a JVM unit test host.
 *
 * [preload] keeps one ad ready at all times — it loads eagerly, and once an ad is shown or
 * fails to show, the next one is requested immediately so [FullScreenAdGate][com.bruni.carscan.core.monetization.FullScreenAdGate]'s
 * next `shouldShow()` has something ready to show.
 *
 * [BuildConfig.INTERSTITIAL_AD_UNIT_ID] is Google's public TEST unit id by default — see this
 * module's `build.gradle.kts` — overridable per machine via the gitignored `local.properties`
 * key `admob.interstitial.adunit`.
 *
 * [show] needs a foreground Activity, read from [CurrentActivity] for the same reason
 * [AdMobRewardedAdPort] does — this port is a Koin singleton scoped to the whole app.
 */
class AdMobInterstitialAdPort(private val context: Context) : InterstitialAdPort {

    @Volatile
    private var ad: InterstitialAd? = null

    @Volatile
    private var loading: Boolean = false

    override fun preload() {
        if (ad != null || loading) return
        loading = true
        InterstitialAd.load(
            context,
            BuildConfig.INTERSTITIAL_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(loaded: InterstitialAd) {
                    loading = false
                    ad = loaded
                }

                override fun onAdFailedToLoad(adError: LoadAdError) {
                    loading = false
                }
            },
        )
    }

    override suspend fun show(): Boolean {
        val activity = CurrentActivity.value ?: return false
        val loaded = ad ?: return false
        ad = null

        val shown = CompletableDeferred<Boolean>()
        loaded.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                preload()
                if (!shown.isCompleted) shown.complete(false)
            }

            override fun onAdDismissedFullScreenContent() {
                preload()
                if (!shown.isCompleted) shown.complete(true)
            }
        }

        loaded.show(activity)
        return shown.await()
    }
}
