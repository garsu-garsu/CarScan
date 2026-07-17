package com.bruni.carscan.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bruni.carscan.core.data.AcquisitionBaseline
import com.bruni.carscan.core.data.ActiveVehicle
import com.bruni.carscan.core.data.AdapterRepository
import com.bruni.carscan.core.data.BookmarkRepository
import com.bruni.carscan.core.data.DashboardLayoutRepository
import com.bruni.carscan.core.data.ObdConnector
import com.bruni.carscan.core.data.SampleSource
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.data.VehicleSessionRepository
import com.bruni.carscan.core.data.VisibleSignals
import com.bruni.carscan.core.monetization.Entitlements
import com.bruni.carscan.core.monetization.FullScreenAdGate
import com.bruni.carscan.core.monetization.InterstitialAdPort
import com.bruni.carscan.core.transport.TransportKind
import com.bruni.carscan.core.transport.fake.ElmEmulator
import com.bruni.carscan.db.CarScanDb
import com.bruni.carscan.feature.connect.ConnectViewModel
import com.bruni.carscan.feature.connect.connectModule
import com.bruni.carscan.feature.dashboard.DashboardViewModel
import com.bruni.carscan.feature.live.LiveViewModel
import com.bruni.carscan.feature.live.liveModule
import com.bruni.carscan.feature.trip.TripListViewModel
import com.bruni.carscan.feature.trip.tripModule
import com.bruni.carscan.nav.AppSettingsOpener
import com.bruni.carscan.obd.AcquisitionController
import com.bruni.carscan.obd.AutoConnector
import com.bruni.carscan.obd.ElmObdConnector
import com.bruni.carscan.obd.FakeTransports
import com.bruni.carscan.obd.TripRecorder
import com.bruni.carscan.obd.Transports
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okio.Path.Companion.toPath
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Every binding the app resolves, resolved.
 *
 * A Koin graph fails at the moment something asks for a type nobody declared — which, for a
 * ViewModel, is the moment the user opens the screen. On a dashboard that means at 70 km/h, in a
 * car, with the phone in a cradle. This test moves that failure to the build.
 *
 * The real [platformModule] cannot be exercised here: its four bindings all want an Android
 * `Context`, and an `androidHostTest` is a plain JVM test with no device. So the platform's *shape*
 * is substituted and everything above it is real — which is where every binding this milestone
 * added actually lives.
 */
class KoinGraphTest {

    private var koin: Koin? = null
    private val preferences = Files.createTempDirectory("carscan-koin")

    @AfterTest
    fun tearDown() {
        koin?.close()
    }

    @Test
    fun `every port the screens depend on resolves`() {
        val koin = start()

        koin.get<ObdConnector>() shouldNotBe null
        koin.get<SampleSource>() shouldNotBe null
        koin.get<ActiveVehicle>() shouldNotBe null
        koin.get<VisibleSignals>() shouldNotBe null

        koin.get<AdapterRepository>() shouldNotBe null
        koin.get<SettingsRepository>() shouldNotBe null
        koin.get<BookmarkRepository>() shouldNotBe null
        koin.get<TripRepository>() shouldNotBe null
        koin.get<DashboardLayoutRepository>() shouldNotBe null
        koin.get<VehicleSessionRepository>() shouldNotBe null
        koin.get<TripRecorder>() shouldNotBe null
        koin.get<AutoConnector>() shouldNotBe null
        koin.get<AcquisitionBaseline>() shouldNotBe null
        koin.get<AcquisitionController>() shouldNotBe null
    }

    /**
     * **The four OBD ports are one object.**
     *
     * Bound as four separate singles, they would be four live sessions: the connect screen would
     * open an adapter, and the dashboard would then read samples from a `SampleSource` that had
     * never connected to anything — a car that answers nothing, which is indistinguishable from a
     * broken adapter and impossible to debug from a bug report.
     */
    @Test
    fun `the connector and the sample source are the same session`() {
        val koin = start()
        val connector = koin.get<ElmObdConnector>()

        (koin.get<ObdConnector>() === connector) shouldBe true
        (koin.get<SampleSource>() === connector) shouldBe true
        (koin.get<ActiveVehicle>() === connector) shouldBe true
        (koin.get<VisibleSignals>() === connector) shouldBe true
    }

    /**
     * The ViewModels, constructed for real.
     *
     * This is the half a declaration check cannot do: it is not that the *types* are declared, it
     * is that each ViewModel's constructor can actually be satisfied from the graph as it stands.
     */
    @Test
    fun `every ViewModel can be constructed`() {
        val koin = start()

        koin.get<ConnectViewModel>() shouldNotBe null
        koin.get<DashboardViewModel>() shouldNotBe null
        koin.get<LiveViewModel>() shouldNotBe null
        koin.get<TripListViewModel>() shouldNotBe null
    }

    private fun start(): Koin = koinApplication {
        modules(appModule(), testPlatformModule(), connectModule, liveModule, tripModule)
    }.koin.also { koin = it }

    /** The shape of [platformModule], with the four things that need a `Context` faked out. */
    private fun testPlatformModule() = module {
        single<SqlDriver> {
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(CarScanDb.Schema::create)
        }

        single<DataStore<Preferences>> {
            PreferenceDataStoreFactory.createWithPath {
                preferences.resolve("carscan.preferences_pb").toString().toPath()
            }
        }

        single<Transports> {
            FakeTransports(supported = setOf(TransportKind.BLE)) { ElmEmulator() }
        }

        single<AppSettingsOpener> { AppSettingsOpener { } }

        // The monetization ports ConnectViewModel now depends on. The real platformModule's
        // versions all want a Context (AdMob, DataStore-backed cache), which this plain-JVM
        // host test has none of — so, same as the four bindings above, only the shape matters.
        single<Entitlements> {
            object : Entitlements {
                override val isPremium: StateFlow<Boolean> = MutableStateFlow(false)
            }
        }
        single { FullScreenAdGate(clock = { 0L }) }
        single<InterstitialAdPort> {
            object : InterstitialAdPort {
                override fun preload() = Unit
                override suspend fun show(): Boolean = false
            }
        }
    }
}
