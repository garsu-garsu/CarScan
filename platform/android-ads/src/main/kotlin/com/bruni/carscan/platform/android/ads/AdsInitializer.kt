package com.bruni.carscan.platform.android.ads

import android.content.Context
import com.google.android.gms.ads.MobileAds
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `MobileAds.initialize()`, exactly once, no matter how many ad ports ask for it.
 *
 * Every AdMob port ([AdMobInterstitialAdPort], [AdMobAppOpenAdPort]) is
 * its own Koin singleton, constructed independently, and each needs the SDK initialized before
 * it can load an ad. Rather than have every constructor call `MobileAds.initialize()` itself,
 * this is the one place that does — call it once, at app start, before any ad port is used.
 */
object AdsInitializer {
    private val initialized = AtomicBoolean(false)

    fun init(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            MobileAds.initialize(context.applicationContext) {}
        }
    }
}
