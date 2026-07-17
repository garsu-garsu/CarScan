package com.bruni.carscan.feature.trip

import app.cash.turbine.test
import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.ActiveTrip
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SignalSeries
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.data.TripSummary
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.SpeedUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TripListViewModelTest {

    private val trips = FakeTrips()
    private val settings = FakeSettings()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = TripListViewModel(trips, settings)

    @Test
    fun `ALL shows every trip, newest first`() = runTest {
        trips.seed(
            trip("newest", startedMs = 2_000, source = "OBD"),
            trip("oldest", startedMs = 1_000, source = "GPS"),
        )
        val vm = viewModel()

        assertEquals(listOf("newest", "oldest"), vm.state.value.trips.map { it.id })
    }

    @Test
    fun `MY_CAR shows only OBD trips`() = runTest {
        trips.seed(
            trip("a", startedMs = 2_000, source = "OBD"),
            trip("b", startedMs = 1_000, source = "GPS"),
        )
        val vm = viewModel()

        vm.onIntent(TripIntent.SetFilter(TripFilter.MY_CAR))

        assertEquals(listOf("a"), vm.state.value.trips.map { it.id })
    }

    @Test
    fun `AUTO shows only GPS trips`() = runTest {
        trips.seed(
            trip("a", startedMs = 2_000, source = "OBD"),
            trip("b", startedMs = 1_000, source = "GPS"),
        )
        val vm = viewModel()

        vm.onIntent(TripIntent.SetFilter(TripFilter.AUTO))

        assertEquals(listOf("b"), vm.state.value.trips.map { it.id })
    }

    @Test
    fun `distance, duration and max speed are carried through untouched`() = runTest {
        trips.seed(
            trip("a", startedMs = 1_000, endedMs = 61_000, distanceM = 1_234.5, maxSpeedKmh = 88.0, source = "OBD"),
        )
        val vm = viewModel()

        val row = vm.state.value.trips.single()
        assertEquals(1_234.5, row.distanceM)
        assertEquals(88.0, row.maxSpeedKmh)
        assertEquals(60_000L, row.durationMs)
    }

    /** `endedMs == null` — still recording — must not crash the duration math. */
    @Test
    fun `a trip still recording has a null duration`() = runTest {
        trips.seed(trip("a", startedMs = 1_000, endedMs = null, source = "OBD"))
        val vm = viewModel()

        assertEquals(null, vm.state.value.trips.single().durationMs)
    }

    /** Tapping a trip card never navigates itself — it asks, and `:composeApp` navigates. */
    @Test
    fun `opening a trip emits an OpenTrip effect with that trip's id`() = runTest {
        trips.seed(trip("a", startedMs = 1_000, source = "OBD"))
        val vm = viewModel()

        vm.effect.test {
            vm.onIntent(TripIntent.OpenTrip("a"))
            assertEquals(TripListEffect.OpenTrip("a"), awaitItem())
        }
    }

    private fun trip(
        id: String,
        startedMs: Long,
        endedMs: Long? = startedMs + 1_000,
        distanceM: Double = 0.0,
        maxSpeedKmh: Double = 0.0,
        source: String,
    ) = TripSummary(
        id = id,
        vehicleId = "veh-1",
        startedMs = startedMs,
        endedMs = endedMs,
        distanceM = distanceM,
        fuelMl = 0.0,
        energyWh = 0.0,
        maxSpeedKmh = maxSpeedKmh,
        idleMs = 0,
        sampleCount = 0,
        source = source,
    )
}

private class FakeTrips : TripRepository {
    private var seeded: List<TripSummary> = emptyList()

    fun seed(vararg trips: TripSummary) {
        seeded = trips.toList()
    }

    override val isRecording: Boolean get() = false
    override val activeTrip: StateFlow<ActiveTrip?> = MutableStateFlow(null).asStateFlow()
    override suspend fun start(vehicleId: String?, startedMs: Long, source: String): String = "trip-1"
    override fun offer(sample: SensorSample) = Unit
    override suspend fun stop(endedMs: Long) = Unit
    override suspend fun trips(vehicleId: String): List<TripSummary> = seeded
    override suspend fun allTrips(): List<TripSummary> = seeded
    override suspend fun summary(tripId: String): TripSummary? = seeded.find { it.id == tripId }
    override suspend fun signalIds(tripId: String): List<String> = emptyList()
    override suspend fun series(tripId: String, signalId: String): SignalSeries? = null
    override suspend fun setStartLocation(tripId: String, lat: Double, lon: Double, address: String?) = Unit
    override suspend fun setEndLocation(tripId: String, lat: Double, lon: Double, address: String?) = Unit
    override suspend fun import(trip: TripSummary, series: List<SignalSeries>) = Unit
    override suspend fun delete(tripId: String) = Unit
    override suspend fun recordEvent(event: com.bruni.carscan.core.data.TripEvent) = Unit
    override suspend fun events(tripId: String): List<com.bruni.carscan.core.data.TripEvent> = emptyList()
    override suspend fun track(tripId: String): List<com.bruni.carscan.core.data.GpsPoint> = emptyList()
}

private class FakeSettings : SettingsRepository {
    private val flow = MutableStateFlow(Settings())
    override val settings: Flow<Settings> = flow

    override suspend fun setUnit(quantity: Quantity, unit: UnitId) = Unit
    override suspend fun setUnits(units: UnitPreferences) = Unit

    @Deprecated("Use setUnit(Quantity.SPEED, …).", ReplaceWith("setUnit(Quantity.SPEED, unit)"))
    override suspend fun setSpeedUnit(unit: SpeedUnit) = Unit
    override suspend fun setRecordTrips(enabled: Boolean) = Unit
    override suspend fun setKeepScreenOn(enabled: Boolean) = Unit
    override suspend fun setActiveVehicleId(id: String?) = Unit
    override suspend fun setThemeMode(mode: ThemeMode) = Unit
    override suspend fun setGaugeStyle(style: String) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit
    override suspend fun setAcquisitionSource(source: AcquisitionSource) = Unit
    override suspend fun setAutoDriveDetectSpeedKmh(kmh: Int) = Unit
    override suspend fun setBackgroundTracking(enabled: Boolean) = Unit
}
