package com.bruni.carscan.core.database

import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TRIP = "trip-1"
private const val VEHICLE = "veh-1"

/**
 * [GpsWriter] mirrors [SampleWriter]'s shape: a non-suspending [GpsWriter.offer], one
 * transaction per flush interval rather than per fix, and gap seconds stored as NaN
 * rather than carried forward. It differs only in what it writes — trip_gps, not
 * trip_series or the trip's totals, which stay SampleWriter's job entirely.
 */
class GpsWriterTest {

    private val raw = createTestDriver()
    private val driver = CountingSqlDriver(raw)
    private val db: CarScanDb = createDatabase(driver)

    private fun seedTrip() {
        db.vehicleQueries.insertOrIgnore(
            id = VEHICLE, vin = null, make = "Kia", model = "EV6", model_year = 2023,
            obdb_repo = "Kia-EV6", display_name = null, protocol_num = 6,
            last_connected_ms = null, created_ms = 0,
        )
        db.tripQueries.insertOrIgnore(
            id = TRIP, vehicle_id = VEHICLE, started_ms = 0, ended_ms = null,
            distance_m = 0.0, fuel_ml = 0.0, energy_wh = 0.0,
            max_speed_kmh = 0.0, idle_ms = 0, sample_count = 0, source = "OBD",
        )
        driver.reset()
    }

    private fun fix(ts: Long, lat: Double, lon: Double, alt: Float = 100f, speed: Float = 50f, bearing: Float = 90f) =
        GpsFixSample(tsMs = ts, lat = lat, lon = lon, altM = alt, speedKmh = speed, bearingDeg = bearing)

    // --- Columnar round trip, with a gap ---------------------------------------

    @Test
    fun `fixes offered across seconds round trip, with a gap stored as NaN`() = runTest {
        seedTrip()
        val writer = GpsWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        writer.offer(fix(ts = 0, lat = 37.0, lon = 127.0))
        advanceTimeBy(1_000)
        advanceTimeBy(1_000) // second 1: no fix at all
        writer.offer(fix(ts = 2_000, lat = 37.002, lon = 127.002))
        advanceTimeBy(1_000)
        writer.stop()
        advanceUntilIdle()

        val chunks = db.tripGpsQueries.selectTrip(TRIP).executeAsList()
        assertEquals(1, chunks.size)
        val chunk = chunks.single()
        assertEquals(0L, chunk.chunk_index)
        assertEquals(0L, chunk.t0_s)
        assertEquals(3L, chunk.n)

        assertEquals(37.0, chunk.lat[0], 1e-9)
        assertEquals(127.0, chunk.lon[0], 1e-9)
        assertTrue(chunk.lat[1].isNaN(), "the gap second must survive as NaN, not 0.0")
        assertTrue(chunk.lon[1].isNaN())
        assertEquals(37.002, chunk.lat[2], 1e-9)
        assertEquals(127.002, chunk.lon[2], 1e-9)

        assertEquals(100f, chunk.alt[0])
        assertEquals(50f, chunk.speed[0])
        assertEquals(90f, chunk.bearing[0])
        assertTrue(chunk.alt[1].isNaN())
        assertTrue(chunk.speed[1].isNaN())
        assertTrue(chunk.bearing[1].isNaN())
    }

    // --- Chunk boundary ----------------------------------------------------------

    @Test
    fun `fixes spanning the ten-minute boundary land in two chunks`() = runTest {
        seedTrip()
        val writer = GpsWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        for (second in 0 until 700) {
            writer.offer(fix(ts = second * 1000L, lat = 37.0 + second * 0.0001, lon = 127.0))
            advanceTimeBy(1_000)
        }
        writer.stop()
        advanceUntilIdle()

        val chunks = db.tripGpsQueries.selectTrip(TRIP).executeAsList()
        assertEquals(2, chunks.size)
        assertEquals(600L, chunks[0].n)
        assertEquals(100L, chunks[1].n)
        assertEquals(600L, chunks[1].t0_s)
        assertEquals(37.0 + 650 * 0.0001, chunks[1].lat[50], 1e-9)
    }

    // --- Multiple fixes in the same second ---------------------------------------

    @Test
    fun `several fixes in one second are averaged into that second's slot`() = runTest {
        seedTrip()
        val writer = GpsWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        writer.offer(fix(ts = 0, lat = 37.0, lon = 127.0))
        writer.offer(fix(ts = 100, lat = 37.002, lon = 127.002))
        advanceTimeBy(1_000)
        writer.stop()
        advanceUntilIdle()

        val chunk = db.tripGpsQueries.selectTrip(TRIP).executeAsOne()
        assertEquals(1, chunk.n)
        assertEquals(37.001, chunk.lat[0], 1e-9)
        assertEquals(127.001, chunk.lon[0], 1e-9)
    }

    // --- offer() must not block the platform's location callback -----------------

    @Test
    fun `offer never suspends when the channel is full, and drops the oldest`() = runTest {
        seedTrip()
        val writer = GpsWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        repeat(10_000) { i -> writer.offer(fix(ts = 0, lat = i.toDouble(), lon = 0.0)) }

        advanceTimeBy(1_001)
        advanceUntilIdle()

        val chunk = db.tripGpsQueries.selectTrip(TRIP).executeAsOne()
        // All 10,000 landed in second 0. DROP_OLDEST keeps the newest 4096: 5904..9999.
        assertEquals((5_904 + 9_999) / 2.0, chunk.lat[0], 0.5)
    }

    // --- Recording off -------------------------------------------------------------

    @Test
    fun `with the writer never started, nothing is written`() = runTest {
        seedTrip()
        val writer = GpsWriter(db, backgroundScope, flushIntervalMs = 1_000)

        writer.offer(fix(ts = 0, lat = 37.0, lon = 127.0))
        advanceTimeBy(2_000)
        advanceUntilIdle()

        assertEquals(0L, db.tripGpsQueries.countAll().executeAsOne())
    }

    // --- Throughput: one transaction per flush, not per fix -----------------------

    @Test
    fun `one flush per second costs one transaction, not one per fix`() = runTest {
        seedTrip()
        val writer = GpsWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        repeat(10 * 20) {
            writer.offer(fix(ts = currentTime, lat = 37.0, lon = 127.0))
            advanceTimeBy(50)
        }
        advanceTimeBy(1)

        assertEquals(10, driver.transactions, "one transaction per second, not per fix")
    }
}
