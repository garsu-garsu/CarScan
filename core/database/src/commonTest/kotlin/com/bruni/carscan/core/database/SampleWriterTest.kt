package com.bruni.carscan.core.database

import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TRIP = "trip-1"
private const val VEHICLE = "veh-1"

class SampleWriterTest {

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
            max_speed_kmh = 0.0, idle_ms = 0, sample_count = 0,
        )
        driver.reset() // the setup rows are not what any of these tests is counting
    }

    private fun speed(kmh: Double, ts: Long) = SensorSample(
        signalId = "VEHICLE_SPEED",
        key = MetricKey.Metric(SuggestedMetric.SPEED),
        value = DecodedValue.Numeric(kmh),
        unit = ObdUnit.KILOMETERS_PER_HOUR,
        timestampMs = ts,
    )

    private fun rpm(value: Double, ts: Long) = SensorSample(
        // OBDb has no `suggestedMetric` for engine RPM — the single most important
        // gauge is addressed by signal id. This is why MetricKey exists.
        signalId = "ENGINE_RPM",
        key = MetricKey.Signal("ENGINE_RPM"),
        value = DecodedValue.Numeric(value),
        unit = ObdUnit.RPM,
        timestampMs = ts,
    )

    private fun series(signalId: String, chunk: Long = 0) =
        db.tripSeriesQueries.selectChunk(TRIP, signalId, chunk).executeAsOneOrNull()

    // --- Throughput ------------------------------------------------------------

    /**
     * The load the real poller produces. A writer that opened a transaction per
     * sample would store exactly the same rows and pass any test that only checked
     * the data landed — while committing 200 times a second. Only the transaction
     * count separates the two designs, so that is what is asserted.
     */
    @Test
    fun `200 samples per second for 10 seconds costs 10 transactions, not 2000`() = runTest {
        seedTrip()
        val writer = SampleWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        // 200 Hz for ten seconds, arriving spread across each second the way the poller
        // actually delivers them — not dumped in a batch, which would let a single
        // flush swallow the lot and prove nothing.
        repeat(10 * 200) {
            writer.offer(speed(60.0, ts = currentTime))
            advanceTimeBy(5)
        }
        // advanceTimeBy does not run a task scheduled at exactly the new time, so the
        // tenth flush is still queued at t=10_000. One more tick lets it fire.
        advanceTimeBy(1)

        assertEquals(10, driver.transactions, "one transaction per second, not per sample")
        // 10 flushes x (1 chunk upsert + 1 totals update). Not 2000 inserts.
        assertEquals(20, driver.executes, "no per-sample statements")
        assertEquals(2_000L, db.tripQueries.selectById(TRIP).executeAsOne().sample_count)
        // 2000 samples became 10 stored values: one per second. This is the 1 Hz cap.
        assertEquals(10, series("VEHICLE_SPEED")!!.series.size)
    }

    // --- offer() must not block the poller -------------------------------------

    /**
     * The channel holds 4096. Ten thousand samples are pushed at it with the drain
     * coroutine never scheduled, so it is full for most of them. If `offer` suspended
     * or blocked, this test would not finish — and in production the OBD poller would
     * be the thing that stopped.
     *
     * The surviving samples must be the NEWEST 4096 (DROP_OLDEST). That is asserted
     * through the stored mean rather than by counting: values 0..9999 are offered,
     * so if the oldest were dropped the mean of what remains is the mean of
     * 5904..9999 = 7951.5, and if the newest were dropped it would be 2047.5.
     */
    @Test
    fun `offer never suspends when the channel is full, and drops the oldest`() = runTest {
        seedTrip()
        val writer = SampleWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        // The drain coroutine is queued on the test scheduler and has not run yet, so
        // nothing is consuming while these 10,000 calls are made.
        repeat(10_000) { i ->
            writer.offer(speed(i.toDouble(), ts = 0)) // all in second 0
        }

        advanceTimeBy(1_001)
        advanceUntilIdle()

        assertEquals(4_096L, db.tripQueries.selectById(TRIP).executeAsOne().sample_count)

        val stored = series("VEHICLE_SPEED")!!
        assertEquals(1, stored.series.size, "all ten thousand landed in second 0")
        assertEquals(7951.5f, stored.series[0], 0.01f)
    }

    // --- Recording OFF ---------------------------------------------------------

    /**
     * Recording off is the default, and the live gauges never touch the disk. So an
     * unrecorded drive must be *zero* database traffic — not "a few rows nobody
     * reads", which is what an unconditional write path would produce for every
     * minute the app is open in someone's car.
     */
    @Test
    fun `with recording off, nothing is written at all`() = runTest {
        seedTrip()
        val writer = SampleWriter(db, backgroundScope, flushIntervalMs = 1_000)
        // start() is never called.

        repeat(1_000) { i -> writer.offer(speed(60.0, ts = i * 50L)) }
        advanceTimeBy(10_000)
        advanceUntilIdle()

        assertEquals(0, driver.transactions)
        assertEquals(0, driver.executes)
        assertEquals(0L, db.tripSeriesQueries.countAll().executeAsOne())
        assertEquals(0L, db.tripQueries.selectById(TRIP).executeAsOne().sample_count)
        assertEquals(0.0, db.tripQueries.selectById(TRIP).executeAsOne().distance_m)

        // And they were not merely left queued: a sample offered while recording was off
        // must never surface later. Otherwise the first seconds of the next trip are
        // back-filled with data from a drive the user did not ask to record.
        writer.start(TRIP, startedMs = 0)
        advanceTimeBy(2_000)

        assertEquals(0L, db.tripSeriesQueries.countAll().executeAsOne())
        assertEquals(0L, db.tripQueries.selectById(TRIP).executeAsOne().sample_count)
    }

    // --- Columnar round trip ---------------------------------------------------

    /**
     * An hour of driving. Written as BLOBs, read back, compared value by value: this
     * is the format every recorded trip is stored in, and a packing bug surfaces not
     * as a crash but as a believable wrong number on a chart months later.
     */
    @Test
    fun `an hour of samples round trips through the columnar store exactly`() = runTest {
        seedTrip()
        val writer = SampleWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        val expected = FloatArray(3_600) { (it % 137) * 0.75f }
        for (second in 0 until 3_600) {
            writer.offer(speed(expected[second].toDouble(), ts = second * 1000L))
            advanceTimeBy(1_000)
        }
        writer.stop(endedMs = 3_600_000)
        advanceUntilIdle()

        val chunks = db.tripSeriesQueries.selectSignal(TRIP, "VEHICLE_SPEED").executeAsList()
        assertEquals(6, chunks.size, "one hour is six ten-minute chunks")

        val readBack = FloatArray(3_600)
        for (chunk in chunks) {
            assertEquals(chunk.chunk_index * SECONDS_PER_CHUNK, chunk.t0_s)
            assertEquals(chunk.series.size.toLong(), chunk.n)
            chunk.series.copyInto(readBack, chunk.t0_s.toInt())
        }
        for (i in expected.indices) {
            assertEquals(expected[i], readBack[i], "second $i")
        }
    }

    /**
     * The whole point of the columnar layout. One row per sample would be 3,600 rows
     * for this hour, per signal.
     */
    @Test
    fun `an hour of one signal is six rows, not thirty-six hundred`() = runTest {
        seedTrip()
        val writer = SampleWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)
        for (second in 0 until 3_600) {
            writer.offer(speed(50.0, ts = second * 1000L))
            advanceTimeBy(1_000)
        }
        writer.stop(endedMs = 3_600_000)
        advanceUntilIdle()

        assertEquals(6L, db.tripSeriesQueries.countAll().executeAsOne())
    }

    // --- Aggregates ------------------------------------------------------------

    @Test
    fun `chunk min, max and avg equal the naive computation over the stored series`() = runTest {
        seedTrip()
        val writer = SampleWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        val values = FloatArray(300) { ((it * 7) % 91).toFloat() + 0.5f }
        for (second in values.indices) {
            writer.offer(speed(values[second].toDouble(), ts = second * 1000L))
            advanceTimeBy(1_000)
        }
        writer.stop(endedMs = 300_000)
        advanceUntilIdle()

        val row = series("VEHICLE_SPEED")!!
        val naiveMin = values.min().toDouble()
        val naiveMax = values.max().toDouble()
        val naiveAvg = values.map { it.toDouble() }.sum() / values.size

        assertEquals(naiveMin, row.min_v!!, 1e-6)
        assertEquals(naiveMax, row.max_v!!, 1e-6)
        assertEquals(naiveAvg, row.avg_v!!, 1e-6)
        assertTrue(row.series.contentEquals(values))
    }

    /**
     * A signal the car did not answer for a few seconds leaves a hole. It is stored
     * as NaN and excluded from the aggregates — a carried-forward value would be
     * indistinguishable from a real reading, and would drag the average with it.
     */
    @Test
    fun `a second with no sample is stored as NaN and ignored by the aggregates`() = runTest {
        seedTrip()
        val writer = SampleWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        writer.offer(rpm(1000.0, ts = 0))
        advanceTimeBy(1_000)
        advanceTimeBy(1_000) // second 1: the ECU said NO DATA
        writer.offer(rpm(2000.0, ts = 2_000))
        advanceTimeBy(1_000)
        writer.stop(endedMs = 3_000)
        advanceUntilIdle()

        val row = series("ENGINE_RPM")!!
        assertEquals(3, row.series.size)
        assertEquals(1000f, row.series[0])
        assertTrue(row.series[1].isNaN(), "the gap must survive as a gap")
        assertEquals(2000f, row.series[2])

        assertEquals(1000.0, row.min_v!!, 1e-6)
        assertEquals(2000.0, row.max_v!!, 1e-6)
        assertEquals(1500.0, row.avg_v!!, 1e-6) // not 1000.0, which averaging the NaN-as-0 would give
    }

    @Test
    fun `many samples in one second are averaged into that second's slot`() = runTest {
        seedTrip()
        val writer = SampleWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        // 20 Hz for one second: 0, 10, 20 ... 190 -> mean 95.
        repeat(20) { i -> writer.offer(speed(i * 10.0, ts = i * 50L)) }
        advanceTimeBy(1_000)
        writer.stop(endedMs = 1_000)
        advanceUntilIdle()

        val row = series("VEHICLE_SPEED")!!
        assertEquals(1, row.series.size, "20 samples in one second must be ONE stored value")
        assertEquals(95f, row.series[0], 1e-3f)
    }

    // --- Trip totals -----------------------------------------------------------

    @Test
    fun `trip totals are derived from the series in the same transaction`() = runTest {
        seedTrip()
        val writer = SampleWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        // 10 s at 0 km/h (idling at a light), then 100 s at 72 km/h (= 20 m/s).
        for (second in 0 until 10) {
            writer.offer(speed(0.0, ts = second * 1000L)); advanceTimeBy(1_000)
        }
        for (second in 10 until 110) {
            writer.offer(speed(72.0, ts = second * 1000L)); advanceTimeBy(1_000)
        }
        writer.stop(endedMs = 110_000)
        advanceUntilIdle()

        val trip = db.tripQueries.selectById(TRIP).executeAsOne()
        assertEquals(2_000.0, trip.distance_m, 0.5) // 100 s x 20 m/s
        assertEquals(72.0, trip.max_speed_kmh, 1e-6)
        assertEquals(10_000L, trip.idle_ms)
        assertEquals(110L, trip.sample_count)
        assertEquals(110_000L, trip.ended_ms)
    }

    /** Totals must keep accumulating across a chunk boundary, not restart at it. */
    @Test
    fun `distance keeps accumulating across the ten-minute chunk boundary`() = runTest {
        seedTrip()
        val writer = SampleWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)

        // 1200 s at 36 km/h (= 10 m/s) spans exactly two chunks -> 12,000 m.
        for (second in 0 until 1_200) {
            writer.offer(speed(36.0, ts = second * 1000L)); advanceTimeBy(1_000)
        }
        writer.stop(endedMs = 1_200_000)
        advanceUntilIdle()

        val trip = db.tripQueries.selectById(TRIP).executeAsOne()
        assertEquals(2L, db.tripSeriesQueries.countAll().executeAsOne())
        assertTrue(abs(trip.distance_m - 12_000.0) < 1.0, "distance was ${trip.distance_m}")
        assertEquals(36.0, trip.max_speed_kmh, 1e-6)
    }

    @Test
    fun `a signal with no metric still gets a series, keyed by signal id`() = runTest {
        seedTrip()
        val writer = SampleWriter(db, backgroundScope, flushIntervalMs = 1_000)
        writer.start(TRIP, startedMs = 0)
        writer.offer(rpm(1726.0, ts = 0))
        advanceTimeBy(1_000)
        writer.stop(endedMs = 1_000)
        advanceUntilIdle()

        assertEquals(1726f, series("ENGINE_RPM")!!.series[0], 1e-3f)
        assertNull(series("VEHICLE_SPEED"))
        // No speed signal means no distance to claim.
        assertEquals(0.0, db.tripQueries.selectById(TRIP).executeAsOne().distance_m)
    }
}
