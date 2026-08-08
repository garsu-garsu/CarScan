package com.bruni.carscan.obd

import com.bruni.carscan.core.data.ActiveTrip
import com.bruni.carscan.core.data.GpsFix
import com.bruni.carscan.core.data.GyroSample
import com.bruni.carscan.core.data.GyroSource
import com.bruni.carscan.core.data.HarshEventType
import com.bruni.carscan.core.data.LocationSource
import com.bruni.carscan.core.data.SignalSeries
import com.bruni.carscan.core.data.TripEvent
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.data.TripSummary
import com.bruni.carscan.core.model.SensorSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HarshEventDetector debounces a maneuver down to one event at its peak severity — see the
 * class KDoc. Every test scripts a fix sequence through the real port shapes (a scriptable
 * [LocationSource]/[GyroSource], an in-memory [TripRepository]) and, where the debounce window
 * needs to close, ends the sequence with one more fix past it — the same way GpsWriter's flush
 * needs a later tick before a buffered write becomes observable.
 */
class HarshEventDetectorTest {

    private fun fix(tsMs: Long, speedKmh: Float, bearingDeg: Float = 0f, lat: Double = 37.0, lon: Double = 127.0) =
        GpsFix(tsMs = tsMs, lat = lat, lon = lon, altM = 0f, speedKmh = speedKmh, bearingDeg = bearingDeg)

    @Test
    fun `a speed jump from standstill emits exactly one HARSH_START, not HARSH_ACCEL`() = runTest {
        val trips = FakeEventTripRepository()
        val location = FakeEventLocationSource()
        val gyro = FakeEventGyroSource()
        val detector = HarshEventDetector(location, gyro, trips) { "e1" }
        trips.start(null, startedMs = 0)

        detector.start(backgroundScope)
        runCurrent()

        location.emit(fix(tsMs = 0, speedKmh = 0f))
        runCurrent()
        location.emit(fix(tsMs = 1_000, speedKmh = 14.4f)) // +4 m/s^2 from a stop
        runCurrent()
        location.emit(fix(tsMs = 5_000, speedKmh = 14.4f)) // past the debounce window: flushes
        runCurrent()

        assertEquals(1, trips.recorded.size)
        assertEquals(HarshEventType.HARSH_START, trips.recorded.single().type)
    }

    @Test
    fun `a hard drop to a stop emits exactly one HARSH_STOP`() = runTest {
        val trips = FakeEventTripRepository()
        val location = FakeEventLocationSource()
        val gyro = FakeEventGyroSource()
        val detector = HarshEventDetector(location, gyro, trips) { "e1" }
        trips.start(null, startedMs = 0)

        detector.start(backgroundScope)
        runCurrent()

        location.emit(fix(tsMs = 0, speedKmh = 18f)) // 5 m/s
        runCurrent()
        location.emit(fix(tsMs = 1_000, speedKmh = 0f)) // -5 m/s^2 down to a stop
        runCurrent()
        location.emit(fix(tsMs = 5_000, speedKmh = 0f)) // past the window: flushes
        runCurrent()

        assertEquals(1, trips.recorded.size)
        assertEquals(HarshEventType.HARSH_STOP, trips.recorded.single().type)
    }

    @Test
    fun `a strong accel from cruising speed emits HARSH_ACCEL, a strong brake not to a stop emits HARSH_BRAKE`() = runTest {
        val trips = FakeEventTripRepository()
        val location = FakeEventLocationSource()
        val gyro = FakeEventGyroSource()
        val detector = HarshEventDetector(location, gyro, trips) { "e${trips.recorded.size + 1}" }
        trips.start(null, startedMs = 0)

        detector.start(backgroundScope)
        runCurrent()

        // Cruising at 50 km/h, a hard accel to 68 km/h in 1s (+5 m/s^2).
        location.emit(fix(tsMs = 0, speedKmh = 50f))
        runCurrent()
        location.emit(fix(tsMs = 1_000, speedKmh = 68f))
        runCurrent()
        location.emit(fix(tsMs = 5_000, speedKmh = 68f)) // flush the accel window
        runCurrent()

        // Then a hard brake back to 50 km/h (not a stop): -5 m/s^2.
        location.emit(fix(tsMs = 6_000, speedKmh = 50f))
        runCurrent()
        location.emit(fix(tsMs = 10_000, speedKmh = 50f)) // flush the brake window
        runCurrent()

        assertEquals(2, trips.recorded.size)
        assertEquals(HarshEventType.HARSH_ACCEL, trips.recorded[0].type)
        assertEquals(HarshEventType.HARSH_BRAKE, trips.recorded[1].type)
    }

    @Test
    fun `a high yaw rate at speed emits HARSH_CORNER`() = runTest {
        val trips = FakeEventTripRepository()
        val location = FakeEventLocationSource()
        val gyro = FakeEventGyroSource()
        val detector = HarshEventDetector(location, gyro, trips) { "e1" }
        trips.start(null, startedMs = 0)

        detector.start(backgroundScope)
        runCurrent()

        location.emit(fix(tsMs = 0, speedKmh = 60f))
        runCurrent()
        // 60 km/h = 16.67 m/s; yaw rate 0.5 rad/s -> aLat = 8.3 m/s^2, well over the 4.0 threshold.
        gyro.emit(GyroSample(tsMs = 900, yawRateRadPerSec = 0.5f))
        runCurrent()
        location.emit(fix(tsMs = 1_000, speedKmh = 60f))
        runCurrent()
        location.emit(fix(tsMs = 5_000, speedKmh = 60f, bearingDeg = 0f)) // flush the corner window
        runCurrent()

        assertEquals(1, trips.recorded.size)
        assertEquals(HarshEventType.HARSH_CORNER, trips.recorded.single().type)
    }

    @Test
    fun `the same sustained hard brake across consecutive fixes emits one event with the peak severity`() = runTest {
        val trips = FakeEventTripRepository()
        val location = FakeEventLocationSource()
        val gyro = FakeEventGyroSource()
        val detector = HarshEventDetector(location, gyro, trips) { "e1" }
        trips.start(null, startedMs = 0)

        detector.start(backgroundScope)
        runCurrent()

        // 100 -> 82 -> 60.4 -> 46 km/h: -5, -6, -4 m/s^2. Peak magnitude is 6.0.
        location.emit(fix(tsMs = 0, speedKmh = 100f))
        runCurrent()
        location.emit(fix(tsMs = 1_000, speedKmh = 82f))
        runCurrent()
        location.emit(fix(tsMs = 2_000, speedKmh = 60.4f))
        runCurrent()
        location.emit(fix(tsMs = 3_000, speedKmh = 46f))
        runCurrent()
        location.emit(fix(tsMs = 7_000, speedKmh = 46f)) // past every window opened above: flushes
        runCurrent()

        assertEquals(1, trips.recorded.size)
        val event = trips.recorded.single()
        assertEquals(HarshEventType.HARSH_BRAKE, event.type)
        assertEquals(6.0, event.severityMs2, 0.05)
    }

    @Test
    fun `gentle driving emits nothing`() = runTest {
        val trips = FakeEventTripRepository()
        val location = FakeEventLocationSource()
        val gyro = FakeEventGyroSource()
        val detector = HarshEventDetector(location, gyro, trips) { "e1" }
        trips.start(null, startedMs = 0)

        detector.start(backgroundScope)
        runCurrent()

        location.emit(fix(tsMs = 0, speedKmh = 40f))
        runCurrent()
        location.emit(fix(tsMs = 1_000, speedKmh = 43f)) // < 1 m/s^2
        runCurrent()
        location.emit(fix(tsMs = 5_000, speedKmh = 40f))
        runCurrent()

        assertTrue(trips.recorded.isEmpty())
    }

    @Test
    fun `no events are recorded when no trip is active`() = runTest {
        val trips = FakeEventTripRepository()
        val location = FakeEventLocationSource()
        val gyro = FakeEventGyroSource()
        val detector = HarshEventDetector(location, gyro, trips) { "e1" }
        // No trips.start() call: activeTrip stays null throughout.

        detector.start(backgroundScope)
        runCurrent()

        location.emit(fix(tsMs = 0, speedKmh = 0f))
        runCurrent()
        location.emit(fix(tsMs = 1_000, speedKmh = 14.4f)) // would be a HARSH_START, if a trip were active
        runCurrent()
        location.emit(fix(tsMs = 5_000, speedKmh = 14.4f))
        runCurrent()

        assertTrue(trips.recorded.isEmpty())
    }
}

private class FakeEventLocationSource : LocationSource {
    private val _fixes = MutableSharedFlow<GpsFix>(replay = 0, extraBufferCapacity = 64)
    override val fixes: Flow<GpsFix> = _fixes.asSharedFlow()

    suspend fun emit(fix: GpsFix) = _fixes.emit(fix)
}

private class FakeEventGyroSource : GyroSource {
    private val _yawRate = MutableSharedFlow<GyroSample>(replay = 0, extraBufferCapacity = 64)
    override val yawRate: Flow<GyroSample> = _yawRate.asSharedFlow()

    suspend fun emit(sample: GyroSample) = _yawRate.emit(sample)
}

/** An in-memory TripRepository: just enough of the contract for HarshEventDetector to drive. */
private class FakeEventTripRepository : TripRepository {
    private val _activeTrip = MutableStateFlow<ActiveTrip?>(null)
    override val activeTrip: StateFlow<ActiveTrip?> = _activeTrip
    override val isRecording: Boolean get() = _activeTrip.value != null

    val recorded = mutableListOf<TripEvent>()

    override suspend fun start(vehicleId: String?, startedMs: Long, source: String): String {
        val id = "trip-1"
        _activeTrip.value = ActiveTrip(id, startedMs)
        return id
    }

    override fun offer(sample: SensorSample) = Unit
    override suspend fun stop(endedMs: Long) {
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

    override suspend fun recordEvent(event: TripEvent) {
        recorded += event
    }

    override suspend fun events(tripId: String): List<TripEvent> = recorded.filter { it.tripId == tripId }
    override suspend fun track(tripId: String): List<com.bruni.carscan.core.data.GpsPoint> = emptyList()
}
