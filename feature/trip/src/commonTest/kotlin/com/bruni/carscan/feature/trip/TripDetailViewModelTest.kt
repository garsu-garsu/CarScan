package com.bruni.carscan.feature.trip

import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.ActiveTrip
import com.bruni.carscan.core.data.GpsPoint
import com.bruni.carscan.core.data.HarshEventType
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.SignalSeries
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.data.TripEvent
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.data.TripSummary
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.units.Quantity
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
import kotlin.test.assertFalse

class TripDetailViewModelTest {

    private val trips = FakeDetailTrips()
    private val settings = FakeDetailSettings()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = TripDetailViewModel(trips, settings)

    /**
     * TripDetailViewModel's whole contract: given a tripId to load, it fetches the summary, the
     * route, and the events, and the state carries all three through untouched.
     */
    @Test
    fun `loading a trip populates the header, route and events`() = runTest {
        trips.summaries["trip-1"] = TripSummary(
            id = "trip-1", vehicleId = "veh-1", startedMs = 1_000, endedMs = 61_000,
            distanceM = 1_200.0, fuelMl = 0.0, energyWh = 0.0, maxSpeedKmh = 88.0,
            idleMs = 0, sampleCount = 60,
            startLat = 37.5665, startLon = 126.9780, endLat = 37.4979, endLon = 127.0276,
            startAddress = "Seoul City Hall", endAddress = "Gangnam Station", source = "OBD",
        )
        trips.tracks["trip-1"] = listOf(GpsPoint(37.5665, 126.9780), GpsPoint(37.4979, 127.0276))
        trips.eventsByTrip["trip-1"] = listOf(
            TripEvent(
                id = "e1", tripId = "trip-1", tsMs = 5_000,
                type = HarshEventType.HARSH_BRAKE, severityMs2 = 4.0, lat = 37.5, lon = 127.0,
            ),
        )
        val vm = viewModel()

        vm.onIntent(TripDetailIntent.Load("trip-1"))

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(1_000L, state.startedMs)
        assertEquals(60_000L, state.durationMs)
        assertEquals(1_200.0, state.distanceM)
        assertEquals(88.0, state.maxSpeedKmh)
        assertEquals("Seoul City Hall", state.startAddress)
        assertEquals("Gangnam Station", state.endAddress)
        assertEquals(
            listOf(GpsPoint(37.5665, 126.9780), GpsPoint(37.4979, 127.0276)),
            state.route,
        )
        assertEquals(HarshEventType.HARSH_BRAKE, state.events.single().type)
    }

    /** A trip still recording has no `endedMs` yet — the duration must not crash on that. */
    @Test
    fun `a trip still recording has a null duration`() = runTest {
        trips.summaries["trip-1"] = TripSummary(
            id = "trip-1", vehicleId = "veh-1", startedMs = 1_000, endedMs = null,
            distanceM = 0.0, fuelMl = 0.0, energyWh = 0.0, maxSpeedKmh = 0.0,
            idleMs = 0, sampleCount = 0,
        )
        val vm = viewModel()

        vm.onIntent(TripDetailIntent.Load("trip-1"))

        assertEquals(null, vm.state.value.durationMs)
    }
}

private class FakeDetailTrips : TripRepository {
    val summaries = mutableMapOf<String, TripSummary>()
    val tracks = mutableMapOf<String, List<GpsPoint>>()
    val eventsByTrip = mutableMapOf<String, List<TripEvent>>()

    override val isRecording: Boolean get() = false
    override val activeTrip: StateFlow<ActiveTrip?> = MutableStateFlow(null).asStateFlow()
    override suspend fun start(vehicleId: String?, startedMs: Long, source: String): String = "trip-1"
    override fun offer(sample: SensorSample) = Unit
    override suspend fun stop(endedMs: Long) = Unit
    override suspend fun trips(vehicleId: String): List<TripSummary> = emptyList()
    override suspend fun allTrips(): List<TripSummary> = emptyList()
    override suspend fun summary(tripId: String): TripSummary? = summaries[tripId]
    override suspend fun signalIds(tripId: String): List<String> = emptyList()
    override suspend fun series(tripId: String, signalId: String): SignalSeries? = null
    override suspend fun setStartLocation(tripId: String, lat: Double, lon: Double, address: String?) = Unit
    override suspend fun setEndLocation(tripId: String, lat: Double, lon: Double, address: String?) = Unit
    override suspend fun import(trip: TripSummary, series: List<SignalSeries>) = Unit
    override suspend fun recoverStranded() = Unit
    override suspend fun delete(tripId: String) = Unit
    override suspend fun recordEvent(event: TripEvent) = Unit
    override suspend fun events(tripId: String): List<TripEvent> = eventsByTrip[tripId] ?: emptyList()
    override suspend fun track(tripId: String): List<GpsPoint> = tracks[tripId] ?: emptyList()
}

private class FakeDetailSettings : SettingsRepository {
    private val flow = MutableStateFlow(Settings())
    override val settings: Flow<Settings> = flow

    override suspend fun setUnit(quantity: Quantity, unit: UnitId) = Unit
    override suspend fun setUnits(units: UnitPreferences) = Unit

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
