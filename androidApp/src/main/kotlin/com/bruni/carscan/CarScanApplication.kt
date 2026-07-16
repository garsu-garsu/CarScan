package com.bruni.carscan

import android.app.Application
import com.bruni.carscan.di.carScanModules
import com.bruni.carscan.obd.TripRecorder
import com.bruni.carscan.platform.android.ads.ActivityTracker
import com.bruni.carscan.platform.android.ads.PlayBillingEntitlements
import kotlinx.coroutines.CoroutineScope
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class CarScanApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // PlayBillingPort and AdMobRewardedAdPort are app-scoped singletons that still need a
        // foreground Activity to launch a purchase sheet or an ad — see CurrentActivity's KDoc.
        registerActivityLifecycleCallbacks(ActivityTracker())

        startKoin {
            androidContext(this@CarScanApplication)
            modules(carScanModules())
        }

        // The recorder must exist before the first sample does. It is the only subscriber that
        // puts anything on disk, and a sample that arrives before it is listening is a second of a
        // drive that was never recorded. It costs nothing while recording is off — which is the
        // default — because all it does then is watch a flow.
        get<TripRecorder>().start(get<CoroutineScope>())

        // Picks up a lapsed subscription or a store-side refund promptly, rather than only the
        // next time the user makes a purchase. isPremium itself needs no network call to be
        // right on a cold start — see DefaultEntitlements' offline cache.
        get<PlayBillingEntitlements>().refresh()
    }
}
