package com.bruni.carscan.core.data

import com.bruni.carscan.core.database.createDatabase
import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripRepositoryTest {

    private val driver = createTestDriver()
    private val db: CarScanDb = createDatabase(driver)

    private fun repo(scope: kotlinx.coroutines.CoroutineScope, ids: Iterator<String>? = null) =
        DefaultTripRepository(
            db = db,
            scope = scope,
            newId = ids?.let { { it.next() } } ?: { newUuid() },
        )

    // --- Recording -------------------------------------------------------------

    @Test
    fun `starting a trip inserts exactly one trip, with a UUID for an id`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)

        val id = repo.start(VEHICLE, startedMs = 0)

        assertEquals(1L, db.tripQueries.countAll().executeAsOne())
        // 8-4-4-4-12 hex. Not an integer, because a backup restored onto another phone
        // would collide with that phone's own trip 1.
        assertTrue(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id),
            "not a v4 UUID: $id",
        )
        assertTrue(repo.isRecording)
    }

    @Test
    fun `two trips never share an id`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)
        val a = repo.start(VEHICLE, startedMs = 0)
        repo.stop(endedMs = 1_000)
        val b = repo.start(VEHICLE, startedMs = 2_000)
        assertNotEquals(a, b)
        assertEquals(2L, db.tripQueries.countAll().executeAsOne())
    }

    @Test
    fun `recording a drive stores the series and the totals`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)
        val id = repo.start(VEHICLE, startedMs = 0)

        // 100 s at 72 km/h = 20 m/s = 2,000 m.
        for (second in 0 until 100) {
            repo.offer(speed(72.0, ts = second * 1000L))
            advanceTimeBy(1_000)
        }
        repo.stop(endedMs = 100_000)
        advanceUntilIdle()

        val summary = repo.summary(id)!!
        assertEquals(2_000.0, summary.distanceM, 0.5)
        assertEquals(72.0, summary.maxSpeedKmh, 1e-6)
        assertEquals(100L, summary.sampleCount)
        assertEquals(100_000L, summary.endedMs)
        assertTrue(!repo.isRecording)
    }

    /**
     * Playback. The chunks are read back and stitched into one array per signal,
     * indexed by second, so scrubbing the timeline is an array index rather than a
     * query — which is the whole reason the store is columnar.
     */
    @Test
    fun `playback reads a whole trip back as one array per signal`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)
        val id = repo.start(VEHICLE, startedMs = 0)

        val expected = FloatArray(1_500) { (it % 97) * 1.5f } // spans three chunks
        for (second in expected.indices) {
            repo.offer(speed(expected[second].toDouble(), ts = second * 1000L))
            repo.offer(rpm(2000.0, ts = second * 1000L))
            advanceTimeBy(1_000)
        }
        repo.stop(endedMs = 1_500_000)
        advanceUntilIdle()

        assertEquals(listOf("ENGINE_RPM", "VEHICLE_SPEED"), repo.signalIds(id))

        val series = repo.series(id, "VEHICLE_SPEED")!!
        assertEquals(1_500, series.values.size, "three chunks stitched back into one array")
        for (i in expected.indices) {
            assertEquals(expected[i], series.values[i], "second $i")
        }
    }

    @Test
    fun `a signal that was never recorded reads back as null`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)
        val id = repo.start(VEHICLE, startedMs = 0)
        repo.offer(rpm(1000.0, ts = 0))
        advanceTimeBy(1_000)
        repo.stop(endedMs = 1_000)
        advanceUntilIdle()

        assertNull(repo.series(id, "VEHICLE_SPEED"))
    }

    // --- Import idempotency ----------------------------------------------------

    /**
     * The requirement that forced UUID ids. A user restores a backup, then restores it
     * again (or restores an overlapping one) — they must end up with their trips, not
     * with two of each. With autoincrement keys this is not merely untested, it is
     * impossible: the second import mints new ids by definition.
     */
    @Test
    fun `importing the same trip twice produces one trip, not two`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)

        val trip = TripSummary(
            id = "11111111-1111-4111-8111-111111111111",
            vehicleId = VEHICLE,
            startedMs = 1_000,
            endedMs = 61_000,
            distanceM = 1_200.0,
            fuelMl = 90.0,
            energyWh = 0.0,
            maxSpeedKmh = 88.0,
            idleMs = 4_000,
            sampleCount = 60,
        )
        val series = listOf(SignalSeries("VEHICLE_SPEED", FloatArray(60) { it.toFloat() }))

        repo.import(trip, series)
        repo.import(trip, series)

        assertEquals(1L, db.tripQueries.countAll().executeAsOne())
        assertEquals(1L, db.tripSeriesQueries.countAll().executeAsOne())

        val restored = repo.summary(trip.id)!!
        assertEquals(1_200.0, restored.distanceM, 1e-6)
        assertEquals(88.0, restored.maxSpeedKmh, 1e-6)
        assertEquals(60, repo.series(trip.id, "VEHICLE_SPEED")!!.values.size)
    }

    @Test
    fun `an imported trip longer than ten minutes comes back with every second intact`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)

        val values = FloatArray(2_000) { (it % 61).toFloat() } // spans four chunks
        val trip = TripSummary(
            id = "22222222-2222-4222-8222-222222222222",
            vehicleId = VEHICLE, startedMs = 0, endedMs = 2_000_000,
            distanceM = 0.0, fuelMl = 0.0, energyWh = 0.0,
            maxSpeedKmh = 60.0, idleMs = 0, sampleCount = 2_000,
        )
        repo.import(trip, listOf(SignalSeries("VEHICLE_SPEED", values)))
        repo.import(trip, listOf(SignalSeries("VEHICLE_SPEED", values)))

        assertEquals(1L, db.tripQueries.countAll().executeAsOne())
        assertEquals(4L, db.tripSeriesQueries.countAll().executeAsOne())

        val back = repo.series(trip.id, "VEHICLE_SPEED")!!.values
        assertEquals(2_000, back.size)
        for (i in values.indices) assertEquals(values[i], back[i], "second $i")
    }

    // --- Deletion --------------------------------------------------------------

    @Test
    fun `deleting a trip takes its series with it`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)
        val id = repo.start(VEHICLE, startedMs = 0)
        repo.offer(speed(50.0, ts = 0))
        advanceTimeBy(1_000)
        repo.stop(endedMs = 1_000)
        advanceUntilIdle()

        assertEquals(1L, db.tripSeriesQueries.countAll().executeAsOne())
        repo.delete(id)
        assertEquals(0L, db.tripQueries.countAll().executeAsOne())
        assertEquals(0L, db.tripSeriesQueries.countAll().executeAsOne())
    }

    @Test
    fun `trips are listed newest first`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)
        val first = repo.start(VEHICLE, startedMs = 1_000); repo.stop(2_000)
        val second = repo.start(VEHICLE, startedMs = 9_000); repo.stop(10_000)
        advanceUntilIdle()

        assertEquals(listOf(second, first), repo.trips(VEHICLE).map { it.id })
    }

    /**
     * DrivingDetector's path: a GPS-only trip started with no adapter connected has no vehicle
     * to attach to. A null FK is allowed — see Trip.sq — and the trip must still show up in
     * allTrips() (the trip-list screen filters by source, not by vehicle).
     */
    @Test
    fun `starting a trip with a null vehicle and source GPS persists and lists with that source`() = runTest {
        val repo = repo(backgroundScope)

        val id = repo.start(vehicleId = null, startedMs = 0, source = "GPS")
        repo.stop(endedMs = 1_000)
        advanceUntilIdle()

        val summary = repo.summary(id)!!
        assertNull(summary.vehicleId)
        assertEquals("GPS", summary.source)

        val listed = repo.allTrips().single()
        assertNull(listed.vehicleId)
        assertEquals("GPS", listed.source)
    }

    @Test
    fun `allTrips lists every vehicle's trips newest first`() = runTest {
        db.seedVehicle(VEHICLE)
        db.seedVehicle("veh-2")
        val repo = repo(backgroundScope)

        val first = repo.start(VEHICLE, startedMs = 1_000); repo.stop(2_000)
        val second = repo.start("veh-2", startedMs = 9_000); repo.stop(10_000)
        advanceUntilIdle()

        assertEquals(listOf(second, first), repo.allTrips().map { it.id })
    }

    // --- activeTrip --------------------------------------------------------------

    /**
     * GpsRecorder (composeApp) drives entirely off this flow: it is how it learns a
     * trip started (to open its own GpsWriter) and ended (to close it). Null must be
     * the value both before the first trip and after every trip stops.
     */
    @Test
    fun `activeTrip is null, becomes the started trip, then null again on stop`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)

        assertNull(repo.activeTrip.value)

        val id = repo.start(VEHICLE, startedMs = 1_000)
        assertEquals(ActiveTrip(id, 1_000), repo.activeTrip.value)

        repo.stop(endedMs = 2_000)
        assertNull(repo.activeTrip.value)
    }

    // --- Start/end location --------------------------------------------------------

    @Test
    fun `setStartLocation and setEndLocation persist and surface in summary`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)
        val id = repo.start(VEHICLE, startedMs = 0)

        repo.setStartLocation(id, lat = 37.5665, lon = 126.9780, address = "Seoul City Hall")
        repo.setEndLocation(id, lat = 37.4979, lon = 127.0276, address = "Gangnam Station")
        repo.stop(endedMs = 1_000)

        val summary = repo.summary(id)!!
        assertEquals(37.5665, summary.startLat)
        assertEquals(126.9780, summary.startLon)
        assertEquals("Seoul City Hall", summary.startAddress)
        assertEquals(37.4979, summary.endLat)
        assertEquals(127.0276, summary.endLon)
        assertEquals("Gangnam Station", summary.endAddress)
    }

    // --- Harsh-driving events ----------------------------------------------------

    /**
     * HarshEventDetector's whole contract with storage: record one, read it back for
     * its trip, in timestamp order — a marker list is drawn chronologically, not by
     * insertion order (which a debounce window can reorder across types).
     */
    @Test
    fun `recorded events read back for their trip, in ts order`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)
        val id = repo.start(VEHICLE, startedMs = 0)

        repo.recordEvent(
            TripEvent(id = "e2", tripId = id, tsMs = 2_000, type = HarshEventType.HARSH_BRAKE, severityMs2 = 4.0, lat = 37.1, lon = 127.1),
        )
        repo.recordEvent(
            TripEvent(id = "e1", tripId = id, tsMs = 1_000, type = HarshEventType.HARSH_ACCEL, severityMs2 = 3.2, lat = 37.0, lon = 127.0),
        )

        val events = repo.events(id)
        assertEquals(listOf("e1", "e2"), events.map { it.id })
        assertEquals(HarshEventType.HARSH_ACCEL, events[0].type)
        assertEquals(3.2, events[0].severityMs2, 1e-9)
        assertEquals(37.0, events[0].lat, 1e-9)
        assertEquals(127.0, events[0].lon, 1e-9)
    }

    // --- GPS track ---------------------------------------------------------------

    /**
     * TripMap's whole contract with storage: the columnar trip_gps chunks (see GpsWriter) come
     * back flattened into one in-order list of points, and a NaN hole — a second GpsWriter never
     * got a fix for — is skipped rather than kept as a point a polyline would have to skip itself.
     */
    @Test
    fun `track flattens trip_gps chunks in order and skips NaN holes`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)
        val id = repo.start(VEHICLE, startedMs = 0)

        db.tripGpsQueries.upsert(
            trip_id = id, chunk_index = 0, t0_s = 0, n = 3,
            lat = doubleArrayOf(37.0, Double.NaN, 37.002),
            lon = doubleArrayOf(127.0, Double.NaN, 127.002),
            alt = floatArrayOf(0f, 0f, 0f), speed = floatArrayOf(0f, 0f, 0f), bearing = floatArrayOf(0f, 0f, 0f),
        )
        db.tripGpsQueries.upsert(
            trip_id = id, chunk_index = 1, t0_s = 600, n = 2,
            lat = doubleArrayOf(37.01, 37.02),
            lon = doubleArrayOf(127.01, 127.02),
            alt = floatArrayOf(0f, 0f), speed = floatArrayOf(0f, 0f), bearing = floatArrayOf(0f, 0f),
        )

        assertEquals(
            listOf(
                GpsPoint(37.0, 127.0),
                GpsPoint(37.002, 127.002),
                GpsPoint(37.01, 127.01),
                GpsPoint(37.02, 127.02),
            ),
            repo.track(id),
        )
    }

    @Test
    fun `track is empty when no GPS was ever recorded for the trip`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)
        val id = repo.start(VEHICLE, startedMs = 0)
        repo.stop(endedMs = 1_000)

        assertEquals(emptyList(), repo.track(id))
    }

    // --- Recovery after a process died mid-trip ------------------------------------
    //
    // `ended_ms` is only ever written by stop(), in the process that called start(). A process
    // the OS kills mid-drive leaves the row open forever, and the trip list showed it as
    // "Recording…" for a trip nothing was recording. Every test here models that death the same
    // way a restart does: a *second* repository over the same database, having never seen the
    // first one's trip.

    @Test
    fun `a trip left open by a dead process, with a route, is finished at its last recorded second`() = runTest {
        val dead = repo(backgroundScope)
        val id = dead.start(vehicleId = null, startedMs = 10_000, source = "GPS")
        // 90 seconds of route, exactly as GpsWriter would have flushed it before the kill.
        db.tripGpsQueries.upsert(
            trip_id = id, chunk_index = 0, t0_s = 0, n = 90,
            lat = DoubleArray(90) { 37.0 }, lon = DoubleArray(90) { 127.0 },
            alt = FloatArray(90), speed = FloatArray(90) { 40f }, bearing = FloatArray(90),
        )

        repo(backgroundScope).recoverStranded()

        // Kept, not deleted — a real drive must survive the app being killed — and ended at the
        // last second it has data for rather than at some later wall clock.
        assertEquals(10_000L + 90_000L, db.tripQueries.selectById(id).executeAsOne().ended_ms)
    }

    /**
     * The phantom rows the bug produced: a false start (one spurious fix at speed) that recorded
     * nothing before the process died. Discarding on *distance* would be wrong — a GPS-only trip
     * has no OBD speed samples, so its distance_m is 0.0 however far it went — so the test drives
     * the case that separates the two: a stranded GPS trip with a route is kept (above) and one
     * with nothing at all is discarded.
     */
    @Test
    fun `a trip left open by a dead process with nothing recorded is discarded`() = runTest {
        val dead = repo(backgroundScope)
        dead.start(vehicleId = null, startedMs = 10_000, source = "GPS")

        repo(backgroundScope).recoverStranded()

        assertEquals(0L, db.tripQueries.countAll().executeAsOne())
    }

    @Test
    fun `recovery leaves the trip this process is recording alone`() = runTest {
        db.seedVehicle()
        val repo = repo(backgroundScope)
        val id = repo.start(VEHICLE, startedMs = 10_000)

        repo.recoverStranded()

        assertTrue(repo.isRecording)
        assertNull(db.tripQueries.selectById(id).executeAsOneOrNull()?.ended_ms)
        assertEquals(1L, db.tripQueries.countAll().executeAsOne())
    }

    @Test
    fun `recovery leaves an already finished trip untouched`() = runTest {
        db.seedVehicle()
        val dead = repo(backgroundScope)
        val id = dead.start(VEHICLE, startedMs = 10_000)
        dead.stop(endedMs = 12_000)
        advanceUntilIdle()

        repo(backgroundScope).recoverStranded()

        assertEquals(12_000L, db.tripQueries.selectById(id).executeAsOne().ended_ms)
    }
}
