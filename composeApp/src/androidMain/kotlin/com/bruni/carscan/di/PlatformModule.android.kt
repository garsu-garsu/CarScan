package com.bruni.carscan.di

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.sqldelight.db.SqlDriver
import com.bruni.carscan.core.database.DriverFactory
import com.bruni.carscan.core.monetization.AppOpenAdPort
import com.bruni.carscan.core.monetization.BillingPort
import com.bruni.carscan.core.monetization.DataStoreEntitlementCache
import com.bruni.carscan.core.monetization.DefaultEntitlements
import com.bruni.carscan.core.monetization.Entitlements
import com.bruni.carscan.core.monetization.EntitlementCache
import com.bruni.carscan.core.monetization.FullScreenAdGate
import com.bruni.carscan.core.monetization.InterstitialAdPort
import com.bruni.carscan.core.monetization.RewardedAdPort
import com.bruni.carscan.core.data.LocationSource
import com.bruni.carscan.core.data.ReverseGeocoder
import com.bruni.carscan.core.transport.ble.BleTransportFactory
import com.bruni.carscan.core.transport.spp.SppTransportFactory
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.platform.android.service.AndroidLoggingServiceController
import com.bruni.carscan.platform.android.service.AndroidReverseGeocoder
import com.bruni.carscan.platform.android.service.FusedLocationSource
import com.bruni.carscan.platform.android.service.LoggingServiceController
import com.bruni.carscan.nav.AppSettingsOpener
import com.bruni.carscan.obd.BackgroundTrackingManager
import com.bruni.carscan.obd.DefaultTransports
import com.bruni.carscan.obd.Transports
import com.bruni.carscan.platform.android.ads.AdMobAppOpenAdPort
import com.bruni.carscan.platform.android.ads.AdMobInterstitialAdPort
import com.bruni.carscan.platform.android.ads.AdMobRewardedAdPort
import com.bruni.carscan.platform.android.ads.PlayBillingEntitlements
import com.bruni.carscan.platform.android.ads.PlayBillingPort
import kotlinx.coroutines.CoroutineScope
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.time.Clock

actual fun platformModule(): Module = module {

    single<SqlDriver> { DriverFactory(androidContext()).createDriver() }

    single<DataStore<Preferences>> {
        val context = androidContext()
        PreferenceDataStoreFactory.createWithPath {
            context.filesDir.resolve(PREFERENCES_FILE).absolutePath.toPath()
        }
    }

    // GPS ports satisfied by the device's fused location + platform geocoder. Bound here in
    // androidMain so the iOS klib never sees play-services — the GpsRecorder that consumes them
    // lives in commonMain and knows only the ports.
    single<LocationSource> { FusedLocationSource(androidContext()) }
    single<ReverseGeocoder> { AndroidReverseGeocoder(androidContext()) }

    // All three transports. Android is the only platform where that sentence is true.
    single<Transports> {
        DefaultTransports(
            ble = BleTransportFactory(),
            spp = SppTransportFactory(androidContext()),
        )
    }

    // Foreground service that keeps DrivingDetector/GpsRecorder alive once the user leaves the
    // app, and the manager that starts/stops it from the backgroundTracking setting — see both
    // classes' KDoc.
    single<LoggingServiceController> { AndroidLoggingServiceController(androidContext()) }
    single { BackgroundTrackingManager(get<SettingsRepository>(), get<LoggingServiceController>()) }

    single<AppSettingsOpener> {
        val context = androidContext()
        AppSettingsOpener {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
            // Started from application context, which has no task of its own to go into.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    // --- Monetization: Play Billing + AdMob ---------------------------------------------
    //
    // Real, :platform:android-ads types — kept out of commonMain and androidMain's only home is
    // this file, matching every other platform binding above. `:composeApp`'s androidMain
    // depends on :platform:android-ads for exactly this; commonMain does not, so the iOS klib
    // never sees Play Billing or AdMob.

    single<EntitlementCache> { DataStoreEntitlementCache(get()) }

    single {
        DefaultEntitlements(clock = { Clock.System.now().toEpochMilliseconds() }, cache = get(), scope = get())
    }
    single<Entitlements> { get<DefaultEntitlements>() }

    // Every purchase query — startup, restore, or a finished purchase sheet — feeds the
    // *complete* current purchase list straight into DefaultEntitlements.updatePurchases. Neither
    // the paywall nor the restore button needs to know that this is how entitlement gets
    // re-resolved; they only ever see the common BillingPort/Entitlements ports.
    single<BillingPort> {
        PlayBillingPort(androidContext(), get<CoroutineScope>()) { purchases ->
            get<DefaultEntitlements>().updatePurchases(purchases)
        }
    }
    single<RewardedAdPort> { AdMobRewardedAdPort(androidContext()) }

    // `CarScanApplication` calls `refresh()` once at startup, the same way it starts `TripRecorder`
    // — picks up a lapsed subscription or a store-side refund promptly rather than only the next
    // time the user makes a purchase.
    single { PlayBillingEntitlements(billing = get(), scope = get()) }

    // The one gate shared by every full-screen ad format (interstitial + app open) — see its
    // KDoc. A Koin singleton so both callers see the same session state.
    single { FullScreenAdGate(clock = { System.currentTimeMillis() }) }
    single<InterstitialAdPort> { AdMobInterstitialAdPort(androidContext()) }
    single<AppOpenAdPort> { AdMobAppOpenAdPort(androidContext()) }
}

/** DataStore requires the `.preferences_pb` suffix; it does not append it. */
private const val PREFERENCES_FILE = "carscan.preferences_pb"
