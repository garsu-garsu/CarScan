package com.bruni.carscan.di

import com.bruni.carscan.core.data.AcquisitionBaseline
import com.bruni.carscan.core.data.ActiveVehicle
import com.bruni.carscan.core.data.AdapterRepository
import com.bruni.carscan.core.data.BookmarkRepository
import com.bruni.carscan.core.data.DashboardLayoutRepository
import com.bruni.carscan.core.data.DefaultAcquisitionBaseline
import com.bruni.carscan.core.data.DefaultAdapterRepository
import com.bruni.carscan.core.data.DefaultBookmarkRepository
import com.bruni.carscan.core.data.DefaultDashboardLayoutRepository
import com.bruni.carscan.core.data.DefaultSettingsRepository
import com.bruni.carscan.core.data.DefaultSignalsetCache
import com.bruni.carscan.core.data.DefaultTripRepository
import com.bruni.carscan.core.data.DefaultVehicleRepository
import com.bruni.carscan.core.data.DefaultVehicleSessionRepository
import com.bruni.carscan.core.data.ObdConnector
import com.bruni.carscan.core.data.SampleSource
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.SignalsetCache
import com.bruni.carscan.core.data.SignalsetProvider
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.data.VehicleCatalog
import com.bruni.carscan.core.data.VehicleRepository
import com.bruni.carscan.core.data.VehicleSessionRepository
import com.bruni.carscan.core.data.VisibleSignals
import com.bruni.carscan.core.database.createDatabase
import com.bruni.carscan.db.CarScanDb
import com.bruni.carscan.feature.connect.connectModule
import com.bruni.carscan.feature.garage.garageModule
import com.bruni.carscan.feature.hud.hudModule
import com.bruni.carscan.feature.dashboard.DashboardClock
import com.bruni.carscan.feature.dashboard.DashboardViewModel
import com.bruni.carscan.feature.live.liveModule
import com.bruni.carscan.feature.settings.settingsModule
import com.bruni.carscan.feature.trip.tripModule
import com.bruni.carscan.obd.AcquisitionController
import com.bruni.carscan.obd.AutoConnector
import com.bruni.carscan.obd.BundledSignalsetSource
import com.bruni.carscan.obd.BundledVehicleCatalog
import com.bruni.carscan.obd.DefaultSignalsetProvider
import com.bruni.carscan.obd.DrivingDetector
import com.bruni.carscan.obd.ElmObdConnector
import com.bruni.carscan.obd.GpsRecorder
import com.bruni.carscan.obd.KtorSignalsetDownloader
import com.bruni.carscan.obd.SignalsetDownloader
import com.bruni.carscan.obd.SignalsetSource
import com.bruni.carscan.obd.TripRecorder
import com.bruni.carscan.obd.VehicleRows
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlin.time.Clock

/**
 * The composition root, and the only place in the app where both sides of a `:core:data` port are
 * visible at once: the ELM327 stack that satisfies it, and the screens that consume it.
 *
 * The four OBD ports are **one object under four names**. `ElmObdConnector` *is* the live session,
 * so binding `SampleSource` to a second instance would give the dashboard a sample stream from a
 * connection nobody ever opened — and it would look exactly like a car that answers nothing.
 */
fun appModule(): Module = module {

    /**
     * The application scope. Not `GlobalScope`, and not a ViewModel's: the OBD session outlives
     * every screen — a user scrolling from the dashboard to the trip list has not disconnected
     * their adapter — and a `SupervisorJob` means one failed pump does not take the session with it.
     */
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single<CarScanDb> { createDatabase(get()) }

    single<AdapterRepository> { DefaultAdapterRepository(get()) }
    single<SettingsRepository> { DefaultSettingsRepository(get()) }
    single<BookmarkRepository> { DefaultBookmarkRepository(get()) }
    single<DashboardLayoutRepository> { DefaultDashboardLayoutRepository(get()) }
    single<TripRepository> { DefaultTripRepository(get(), get()) }
    single<VehicleRepository> { DefaultVehicleRepository(get()) }
    single<VehicleCatalog> { BundledVehicleCatalog() }

    single<SignalsetCache> { DefaultSignalsetCache(get()) }
    // A plain HTTPS GET to raw.githubusercontent.com — nothing to do with the ktor-network raw TCP
    // the Wi-Fi ELM327 transport uses. The engine (okhttp/darwin) is on the platform classpath and
    // is created lazily, so this makes no call at construction.
    single { HttpClient() }
    single<SignalsetDownloader> { KtorSignalsetDownloader(get()) }
    single<SignalsetProvider> { DefaultSignalsetProvider(cache = get(), downloader = get()) }

    single<SignalsetSource> {
        BundledSignalsetSource(modelYear = currentYear(), settings = get(), vehicles = get(), provider = get())
    }
    single { VehicleRows(get()) }

    // One instance, four ports. See the KDoc above.
    single { ElmObdConnector(transports = get(), signalsets = get(), scope = get()) }
    single<ObdConnector> { get<ElmObdConnector>() }
    single<SampleSource> { get<ElmObdConnector>() }
    single<ActiveVehicle> { get<ElmObdConnector>() }
    single<VisibleSignals> { get<ElmObdConnector>() }

    single<VehicleSessionRepository> { DefaultVehicleSessionRepository(get(), get()) }

    single<AcquisitionBaseline> { DefaultAcquisitionBaseline() }

    // Keeps the poller on the selected acquisition source's signals while no data screen is in
    // the foreground — see the class KDoc. Registered alongside TripRecorder/AutoConnector
    // because it is started the same way, from CarScanApplication, at launch.
    single {
        AcquisitionController(settings = get(), baseline = get(), visibility = get(), bookmarks = get())
    }

    single {
        TripRecorder(
            source = get(),
            trips = get(),
            settings = get(),
            vehicleId = get<VehicleRows>()::current,
            nowMs = { Clock.System.now().toEpochMilliseconds() },
        )
    }

    // The one attempt to reconnect to the last scanner that reached a working session — see the
    // class KDoc. Registered alongside TripRecorder because it is started the same way, from
    // CarScanApplication, at launch.
    single { AutoConnector(connector = get(), source = get(), adapters = get(), settings = get()) }

    // Records the trip's GPS route + start/arrival address alongside the OBD samples. Driven by
    // TripRepository.activeTrip, so it needs no clock or connection state of its own. The
    // LocationSource/ReverseGeocoder it consumes are bound per platform (Android: fused location).
    single { GpsRecorder(db = get(), trips = get(), location = get(), geocoder = get()) }

    // Detects driving from GPS speed alone and records a GPS-only trip when nothing else is —
    // see the class KDoc. Registered and started the same way as TripRecorder/AutoConnector.
    single {
        DrivingDetector(
            location = get(),
            source = get(),
            connector = get(),
            adapters = get(),
            trips = get(),
            settings = get(),
            nowMs = { Clock.System.now().toEpochMilliseconds() },
        )
    }

    single { DashboardClock.system() }

    // :feature:dashboard ships no Koin module of its own yet, so its ViewModel is declared here.
    // Spelled out rather than `viewModelOf(::DashboardViewModel)`, for the reason :feature:live
    // gives: the reflective form resolves *every* constructor parameter and ignores Kotlin's
    // defaults, so it would demand a binding for the ticker flow and fail at runtime.
    viewModel { DashboardViewModel(get(), get(), get(), get(), get(), get(), get()) }
}

/** The whole graph: this module, the platform's, and each feature's. */
fun carScanModules(): List<Module> = listOf(
    appModule(),
    platformModule(),
    connectModule,
    liveModule,
    settingsModule,
    garageModule,
    hudModule,
    tripModule,
)

/**
 * The model year assumed when nothing has told us what the car is.
 *
 * Read from the clock rather than written down, because a literal here would go stale silently and
 * a signalset filtered to the wrong year drops commands without an error. It selects everything
 * today in any case — the bundled SAE J1979 set declares no year filters — and starts mattering
 * the moment a vehicle's own signalset joins the union.
 */
private fun currentYear(): Int =
    Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).year
