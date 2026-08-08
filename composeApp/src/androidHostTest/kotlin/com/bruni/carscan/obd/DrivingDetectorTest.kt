package com.bruni.carscan.obd

import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.ActiveTrip
import com.bruni.carscan.core.data.AdapterQuirks
import com.bruni.carscan.core.data.AdapterRepository
import com.bruni.carscan.core.data.AdapterSummary
import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.ConnectOutcome
import com.bruni.carscan.core.data.ConnectionState
import com.bruni.carscan.core.data.GpsFix
import com.bruni.carscan.core.data.LocationSource
import com.bruni.carscan.core.data.ObdConnector
import com.bruni.carscan.core.data.SampleSource
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.SignalSeries
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.data.TripSummary
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val STOP_DEBOUNCE_MS = 60_000L

/**
 * DrivingDetector is a debounced speed state machine off `LocationSource.fixes`, combined with
 * `SampleSource.health` so an OBD reconnect mid-trip can hand the GPS-only trip off. Every test
 * drives it through the real dependency shapes (a scriptable [ObdConnector], a settable
 * [SampleSource] health, an in-memory [TripRepository]) rather than poking internals — the same
 * approach GpsRecorderTest and AutoConnectorTest take.
 */
class DrivingDetectorTest {

    private fun fix(speedKmh: Float, tsMs: Long = 0) =
        GpsFix(tsMs = tsMs, lat = 37.0, lon = 127.0, altM = 0f, speedKmh = speedKmh, bearingDeg = 0f)

    @Test
    fun `speed above threshold while disconnected with no remembered adapter starts a GPS-only trip`() = runTest {
        val trips = FakeTripRepository()
        val source = FakeDetectorSampleSource()
        val adapters = FakeDetectorAdapterRepository(remembered = null)
        val connector = FakeDetectorObdConnector { ConnectOutcome.Failed(ConnectFailure.ADAPTER_UNREACHABLE) }
        val location = FakeDetectorLocationSource()
        val detector = DrivingDetector(
            location, source, connector, adapters, trips, FakeDetectSettings(20),
        ) { 1_000L }

        detector.start(backgroundScope)
        runCurrent()
        location.emit(fix(speedKmh = 30f))
        runCurrent()

        assertEquals(listOf(Triple<String?, Long, String>(null, 1_000L, "GPS")), trips.started)
        assertEquals(0, connector.connectCalls)
    }

    @Test
    fun `speed above threshold while disconnected with an adapter that reconnects successfully takes the OBD path`() =
        runTest {
            val trips = FakeTripRepository()
            val source = FakeDetectorSampleSource()
            val quirks = quirks("addr-1")
            val adapters = FakeDetectorAdapterRepository(remembered = quirks)
            val connector = FakeDetectorObdConnector {
                source.setConnection(ConnectionState.CONNECTED)
                ConnectOutcome.Ready(AdapterSummary("fake", isStn = false, protocolNum = null, rttMs = 10, quirks = quirks))
            }
            val location = FakeDetectorLocationSource()
            val detector = DrivingDetector(
                location, source, connector, adapters, trips, FakeDetectSettings(20),
            ) { 0L }

            detector.start(backgroundScope)
            runCurrent()
            location.emit(fix(speedKmh = 30f))
            runCurrent()

            assertEquals(1, connector.connectCalls)
            assertTrue(trips.started.isEmpty())
        }

    @Test
    fun `after driving, speed below threshold for the debounce window stops the GPS trip`() = runTest {
        val trips = FakeTripRepository()
        val source = FakeDetectorSampleSource()
        val adapters = FakeDetectorAdapterRepository(remembered = null)
        val connector = FakeDetectorObdConnector { ConnectOutcome.Failed(ConnectFailure.ADAPTER_UNREACHABLE) }
        val location = FakeDetectorLocationSource()
        val detector = DrivingDetector(
            location, source, connector, adapters, trips, FakeDetectSettings(20),
        ) { currentTime }

        detector.start(backgroundScope)
        runCurrent()

        location.emit(fix(speedKmh = 30f))
        runCurrent()
        assertEquals(1, trips.started.size)

        location.emit(fix(speedKmh = 5f))
        runCurrent()
        advanceTimeBy(STOP_DEBOUNCE_MS)
        runCurrent()

        assertEquals(1, trips.stopped.size)
    }

    @Test
    fun `a brief dip below threshold shorter than the debounce window does not stop the trip`() = runTest {
        val trips = FakeTripRepository()
        val source = FakeDetectorSampleSource()
        val adapters = FakeDetectorAdapterRepository(remembered = null)
        val connector = FakeDetectorObdConnector { ConnectOutcome.Failed(ConnectFailure.ADAPTER_UNREACHABLE) }
        val location = FakeDetectorLocationSource()
        val detector = DrivingDetector(
            location, source, connector, adapters, trips, FakeDetectSettings(20),
        ) { currentTime }

        detector.start(backgroundScope)
        runCurrent()

        location.emit(fix(speedKmh = 30f))
        runCurrent()

        location.emit(fix(speedKmh = 5f)) // dip begins
        runCurrent()
        advanceTimeBy(STOP_DEBOUNCE_MS / 2)
        runCurrent()

        // Back above threshold, and *still driving* — the fixes keep coming, as they do at 1 Hz
        // from a phone in a moving car. Emitting one and then going silent for a whole debounce
        // window would be the other case entirely (fixes stopped), which has its own test below.
        repeat(7) {
            location.emit(fix(speedKmh = 25f))
            runCurrent()
            advanceTimeBy(STOP_DEBOUNCE_MS / 6)
            runCurrent()
        }

        assertTrue(trips.stopped.isEmpty())
    }

    /**
     * The other half of the stuck-"Recording…" bug, and the half that needs no process death:
     * fixes simply stop. A phone parked with the screen off, location permission revoked mid-drive,
     * GPS lost in an underground car park — the flow goes quiet and never delivers the slow fix
     * that used to be the only thing that armed the stop debounce, so the trip stayed open for as
     * long as the process lived. Quiet must end a drive exactly the way slow does.
     */
    @Test
    fun `a trip ends when fixes stop arriving altogether, with no slow fix to trigger it`() = runTest {
        val trips = FakeTripRepository()
        val source = FakeDetectorSampleSource()
        val adapters = FakeDetectorAdapterRepository(remembered = null)
        val connector = FakeDetectorObdConnector { ConnectOutcome.Failed(ConnectFailure.ADAPTER_UNREACHABLE) }
        val location = FakeDetectorLocationSource()
        val detector = DrivingDetector(
            location, source, connector, adapters, trips, FakeDetectSettings(20),
        ) { currentTime }

        detector.start(backgroundScope)
        runCurrent()

        location.emit(fix(speedKmh = 30f))
        runCurrent()
        assertEquals(1, trips.started.size)

        // Nothing further ever arrives — not a slow fix, not a health change.
        advanceTimeBy(STOP_DEBOUNCE_MS)
        runCurrent()

        assertEquals(1, trips.stopped.size)
    }

    @Test
    fun `OBD connecting while a GPS-only trip is active ends that trip`() = runTest {
        val trips = FakeTripRepository()
        val source = FakeDetectorSampleSource()
        val adapters = FakeDetectorAdapterRepository(remembered = null)
        val connector = FakeDetectorObdConnector { ConnectOutcome.Failed(ConnectFailure.ADAPTER_UNREACHABLE) }
        val location = FakeDetectorLocationSource()
        val detector = DrivingDetector(
            location, source, connector, adapters, trips, FakeDetectSettings(20),
        ) { currentTime }

        detector.start(backgroundScope)
        runCurrent()

        location.emit(fix(speedKmh = 30f))
        runCurrent()
        assertEquals(1, trips.started.size)

        // e.g. AutoConnector or a manual connect reconnected independently of this detector.
        source.setConnection(ConnectionState.CONNECTED)
        runCurrent()

        assertEquals(1, trips.stopped.size)
    }

    @Test
    fun `never starts a second trip when one is already active`() = runTest {
        val trips = FakeTripRepository()
        trips.seedActive("existing", startedMs = 0L)
        val source = FakeDetectorSampleSource()
        val adapters = FakeDetectorAdapterRepository(remembered = null)
        val connector = FakeDetectorObdConnector { ConnectOutcome.Failed(ConnectFailure.ADAPTER_UNREACHABLE) }
        val location = FakeDetectorLocationSource()
        val detector = DrivingDetector(
            location, source, connector, adapters, trips, FakeDetectSettings(20),
        ) { 5_000L }

        detector.start(backgroundScope)
        runCurrent()
        location.emit(fix(speedKmh = 30f))
        runCurrent()

        assertTrue(trips.started.isEmpty()) // the detector must not have called start() itself
    }

    private fun quirks(address: String) = AdapterQuirks(
        address = address, kind = TransportKind.BLE.name, name = "Fake",
        gattService = null, gattWrite = null, gattNotify = null,
        isStn = false, supportsExpectedFrames = false, echoSuppressionNeeded = false,
        maxWriteChunk = 20, protocolNum = null, ewmaRttMs = null, lastUsedMs = null,
    )
}

private class FakeDetectorLocationSource : LocationSource {
    private val _fixes = MutableSharedFlow<GpsFix>(replay = 0, extraBufferCapacity = 64)
    override val fixes: Flow<GpsFix> = _fixes.asSharedFlow()

    suspend fun emit(fix: GpsFix) = _fixes.emit(fix)
}

private class FakeDetectorSampleSource : SampleSource {
    override val samples: SharedFlow<SensorSample> = MutableSharedFlow()
    private val _health = MutableStateFlow(SessionHealth())
    override val health: StateFlow<SessionHealth> = _health

    fun setConnection(state: ConnectionState) {
        _health.value = _health.value.copy(connection = state)
    }
}

private class FakeDetectorObdConnector(private val onConnect: suspend () -> ConnectOutcome) : ObdConnector {
    override val supported: Set<TransportKind> = setOf(TransportKind.BLE)
    var connectCalls = 0
        private set

    override fun discover(kind: TransportKind): Flow<DiscoveredAdapter> = MutableSharedFlow()

    override suspend fun connect(target: DiscoveredAdapter, remembered: AdapterQuirks?): ConnectOutcome {
        connectCalls++
        return onConnect()
    }

    override suspend fun disconnect() = Unit
}

private class FakeDetectorAdapterRepository(private val remembered: AdapterQuirks?) : AdapterRepository {
    override suspend fun remember(quirks: AdapterQuirks) = Unit
    override suspend fun recall(address: String): AdapterQuirks? = null
    override suspend fun all(): List<AdapterQuirks> = emptyList()
    override suspend fun updateRtt(address: String, ewmaRttMs: Double, atMs: Long) = Unit
    override suspend fun forget(address: String) = Unit
    override suspend fun lastUsed(): AdapterQuirks? = remembered
}

/** An in-memory TripRepository: just enough of the contract for DrivingDetector to drive. */
private class FakeTripRepository : TripRepository {
    private val _activeTrip = MutableStateFlow<ActiveTrip?>(null)
    override val activeTrip: StateFlow<ActiveTrip?> = _activeTrip
    override val isRecording: Boolean get() = _activeTrip.value != null

    /** Every (vehicleId, startedMs, source) passed to [start], in call order. */
    val started = mutableListOf<Triple<String?, Long, String>>()

    /** Every endedMs passed to [stop], in call order. */
    val stopped = mutableListOf<Long>()
    private var nextId = 0

    /** Seeds an already-active trip directly, without going through [start] — simulates an OBD trip. */
    fun seedActive(id: String, startedMs: Long) {
        _activeTrip.value = ActiveTrip(id, startedMs)
    }

    override suspend fun start(vehicleId: String?, startedMs: Long, source: String): String {
        check(!isRecording) { "already recording" }
        val id = "trip-${nextId++}"
        started += Triple(vehicleId, startedMs, source)
        _activeTrip.value = ActiveTrip(id, startedMs)
        return id
    }

    override fun offer(sample: SensorSample) = Unit

    override suspend fun stop(endedMs: Long) {
        stopped += endedMs
        _activeTrip.value = null
    }

    override suspend fun trips(vehicleId: String): List<TripSummary> = emptyList()
    override suspend fun allTrips(): List<TripSummary> = emptyList()
    override suspend fun summary(tripId: String): TripSummary? = null
    override suspend fun signalIds(tripId: String): List<String> = emptyList()
    override suspend fun series(tripId: String, signalId: String): SignalSeries? = null
    override suspend fun setStartLocation(tripId: String, lat: Double, lon: Double, address: String?) = Unit
    override suspend fun setEndLocation(tripId: String, lat: Double, lon: Double, address: String?) = Unit
    override suspend fun import(trip: TripSummary, series: List<SignalSeries>) = Unit
    override suspend fun recoverStranded() = Unit
    override suspend fun delete(tripId: String) = Unit
    override suspend fun recordEvent(event: com.bruni.carscan.core.data.TripEvent) = Unit
    override suspend fun events(tripId: String): List<com.bruni.carscan.core.data.TripEvent> = emptyList()
    override suspend fun track(tripId: String): List<com.bruni.carscan.core.data.GpsPoint> = emptyList()
}

private class FakeDetectSettings(thresholdKmh: Int) : SettingsRepository {
    override val settings: Flow<Settings> = MutableStateFlow(Settings(autoDriveDetectSpeedKmh = thresholdKmh))

    override suspend fun setRecordTrips(enabled: Boolean) = Unit
    override suspend fun setUnit(quantity: Quantity, unit: UnitId) = Unit
    override suspend fun setUnits(units: UnitPreferences) = Unit

    override suspend fun setKeepScreenOn(enabled: Boolean) = Unit
    override suspend fun setActiveVehicleId(id: String?) = Unit
    override suspend fun setThemeMode(mode: ThemeMode) = Unit
    override suspend fun setGaugeStyle(style: String) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit
    override suspend fun setAcquisitionSource(source: AcquisitionSource) = Unit
    override suspend fun setAutoDriveDetectSpeedKmh(kmh: Int) = Unit
    override suspend fun setBackgroundTracking(enabled: Boolean) = Unit
}
