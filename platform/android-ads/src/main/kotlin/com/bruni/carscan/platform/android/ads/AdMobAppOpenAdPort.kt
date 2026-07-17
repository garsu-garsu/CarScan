package com.bruni.carscan.platform.android.ads

import android.content.Context
import com.bruni.carscan.core.monetization.AppOpenAdPort
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real AdMob app-open ad. Device-tested only, same reason as [AdMobRewardedAdPort]: there is no
 * ad inventory on a JVM unit test host.
 *
 * [preload] keeps one ad ready at all times, the same shape as [AdMobInterstitialAdPort] — it
 * loads eagerly, and once an ad is shown or fails to show, the next one is requested immediately.
 *
 * [BuildConfig.APP_OPEN_AD_UNIT_ID] is Google's public TEST unit id by default — see this
 * module's `build.gradle.kts` — overridable per machine via the gitignored `local.properties`
 * key `admob.appopen.adunit`.
 *
 * [show] needs a foreground Activity, read from [CurrentActivity] for the same reason
 * [AdMobRewardedAdPort] does — this port is a Koin singleton scoped to the whole app.
 */
class AdMobAppOpenAdPort(private val context: Context) : AppOpenAdPort {

    @Volatile
    private var ad: AppOpenAd? = null

    @Volatile
    private var loading: Boolean = false

    override fun preload() {
        if (ad != null || loading) return
        loading = true
        AppOpenAd.load(
            context,
            BuildConfig.APP_OPEN_AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(loaded: AppOpenAd) {
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
        // AdMob drives the ad through a WebView, and every WebView call must be on the main
        // thread. This port is invoked from the app-wide Dispatchers.Default scope
        // (AppOpenAdManager.onStart), so hop to Main before handing off to the SDK — otherwise
        // show() throws "A WebView method was called on thread 'DefaultDispatcher-worker-N'".
        withContext(Dispatchers.Main) {
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
        }
        return shown.await()
    }
}
