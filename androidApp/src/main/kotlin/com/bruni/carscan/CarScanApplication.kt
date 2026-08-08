package com.bruni.carscan

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.monetization.AppOpenAdPort
import com.bruni.carscan.core.monetization.Entitlements
import com.bruni.carscan.core.monetization.FullScreenAdGate
import com.bruni.carscan.core.monetization.InterstitialAdPort
import com.bruni.carscan.di.carScanModules
import com.bruni.carscan.obd.AcquisitionController
import com.bruni.carscan.obd.AutoConnector
import com.bruni.carscan.obd.BackgroundTrackingManager
import com.bruni.carscan.obd.DrivingDetector
import com.bruni.carscan.obd.GpsRecorder
import com.bruni.carscan.obd.HarshEventDetector
import com.bruni.carscan.obd.TripRecorder
import com.bruni.carscan.platform.android.ads.ActivityTracker
import com.bruni.carscan.platform.android.ads.AdsInitializer
import com.bruni.carscan.platform.android.ads.PlayBillingEntitlements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class CarScanApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Exactly once, before any ad port loads a single ad — see AdsInitializer's KDoc.
        AdsInitializer.init(this)

        // PlayBillingPort and AdMobRewardedAdPort are app-scoped singletons that still need a
        // foreground Activity to launch a purchase sheet or an ad — see CurrentActivity's KDoc.
        registerActivityLifecycleCallbacks(ActivityTracker())

        startKoin {
            androidContext(this@CarScanApplication)
            modules(carScanModules())
        }

        // Before any of the recorders below can open a trip: close out the ones a previous
        // process left open. `ended_ms` is only ever written by the process that started the
        // trip, so a process the OS killed mid-drive leaves a row that reads "Recording…"
        // forever — see TripRepository.recoverStranded.
        get<CoroutineScope>().launch { get<TripRepository>().recoverStranded() }

        // The recorder must exist before the first sample does. It is the only subscriber that
        // puts anything on disk, and a sample that arrives before it is listening is a second of a
        // drive that was never recorded. It costs nothing while recording is off — which is the
        // default — because all it does then is watch a flow.
        get<TripRecorder>().start(get<CoroutineScope>())

        // The one attempt to reconnect to the last scanner that reached a working session — see
        // AutoConnector's KDoc. Started the same way as the recorder above: before anything on
        // screen could have raced it into a manual connect.
        get<AutoConnector>().start(get<CoroutineScope>())

        // Keeps the poller (and, with it, trip recording) alive once the user leaves the
        // dashboard — see the class KDoc. Started the same way as the recorder and the
        // auto-connector above.
        get<AcquisitionController>().start(get<CoroutineScope>())

        // Records the trip's GPS route + start/arrival address. Like the recorder above it just
        // watches TripRepository.activeTrip until a trip actually starts, so it costs nothing on a
        // launch that never records — and nothing at all if location permission was declined.
        get<GpsRecorder>().start(get<CoroutineScope>())

        // Detects driving from GPS speed alone and records a GPS-only trip when nothing else
        // is — see DrivingDetector's class KDoc. Started the same way as the recorder and the
        // auto-connector above, so it runs for as long as the process is alive.
        get<DrivingDetector>().start(get<CoroutineScope>())

        // Detects harsh-driving maneuvers (hard accel/brake/corner) off the same GPS stream as
        // the recorder above — see HarshEventDetector's class KDoc. Started the same way.
        get<HarshEventDetector>().start(get<CoroutineScope>())

        // Keeps the process (and, with it, DrivingDetector/GpsRecorder) alive in the background
        // once the user opts in via Settings — see the class KDoc.
        get<BackgroundTrackingManager>().start(get<CoroutineScope>())

        // Picks up a lapsed subscription or a store-side refund promptly, rather than only the
        // next time the user makes a purchase. isPremium itself needs no network call to be
        // right on a cold start — see DefaultEntitlements' offline cache.
        get<PlayBillingEntitlements>().refresh()

        // Full-screen ads: both formats preload eagerly so the first disconnect or the first
        // return to the foreground already has something ready to show.
        get<InterstitialAdPort>().preload()
        get<AppOpenAdPort>().preload()

        ProcessLifecycleOwner.get().lifecycle.addObserver(
            AppOpenAdManager(
                appOpenAd = get<AppOpenAdPort>(),
                gate = get<FullScreenAdGate>(),
                entitlements = get<Entitlements>(),
                scope = get<CoroutineScope>(),
            ),
        )
    }
}
