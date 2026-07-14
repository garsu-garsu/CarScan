package com.bruni.carscan.core.database

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.model.asDoubleOrNull
import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Seconds in one stored chunk. Ten minutes. */
const val SECONDS_PER_CHUNK: Int = 600

/**
 * The only thing that writes trip data, and it never runs on the poller's thread.
 *
 * Three properties matter, in this order:
 *
 * 1. **[offer] must not suspend.** It is called from the OBD polling loop. If the
 *    disk stalls — and on a cheap Android device under memory pressure it will — a
 *    suspending write path stalls the poller, which stalls the ELM327 half-duplex
 *    exchange, which desynchronizes the prompt stream for the rest of the session.
 *    A dropped sample costs one pixel on a chart. A stalled poller costs the drive.
 *    So the channel is DROP_OLDEST and [offer] is a plain `fun`.
 *
 * 2. **One transaction per second, not one per sample.** At 200 samples/sec, a
 *    transaction per sample is 200 commits/sec. Both designs finish, and both store
 *    the same rows — only a test that counts transactions can tell them apart.
 *
 * 3. **Nothing is written when recording is off**, which is the default. The live
 *    gauges read the sample stream, not the disk, so an unrecorded drive does zero
 *    database traffic.
 *
 * Samples are averaged into 1-second buckets and stored one float per second, in the
 * native unit they arrived in. Nothing above 1 Hz is ever persisted: the 20 Hz live
 * chart draws from an in-memory ring buffer in the ViewModel.
 */
class SampleWriter(
    private val db: CarScanDb,
    private val scope: CoroutineScope,
    private val flushIntervalMs: Long = 1_000L,
) {
    private val channel = Channel<SensorSample>(
        capacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var job: Job? = null
    private var tripId: String? = null
    private var startedMs: Long = 0

    /** chunkIndex -> signalId -> the chunk being accumulated. */
    private val live = LinkedHashMap<Long, LinkedHashMap<String, SignalChunk>>()

    /** Which metric a signal id carries, so the totals can find speed, fuel rate, etc. */
    private val metricOf = HashMap<String, MetricKey>()

    /** Contribution of chunks already written and evicted from [live]. */
    private var base = Totals()

    private var currentChunk: Long = 0
    private var sampleCount: Long = 0
    private var pendingSinceFlush: Int = 0

    val isRecording: Boolean get() = tripId != null

    /**
     * Starts recording into [tripId]. The trip row must already exist: the writer
     * maintains a trip's totals, it does not own its lifecycle.
     */
    fun start(tripId: String, startedMs: Long) {
        check(this.tripId == null) { "already recording ${this.tripId}" }
        this.tripId = tripId
        this.startedMs = startedMs
        live.clear()
        metricOf.clear()
        base = Totals()
        currentChunk = 0
        sampleCount = 0
        pendingSinceFlush = 0
        job = scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                drain()
                flush()
            }
        }
    }

    /** Never suspends. Drops the oldest queued sample rather than block the caller. */
    fun offer(sample: SensorSample) {
        if (tripId == null) return
        channel.trySend(sample)
    }

    /** Drains what is queued, writes the final chunks, and closes the trip out. */
    suspend fun stop(endedMs: Long) {
        val trip = tripId ?: return
        job?.cancel()
        job = null
        drain()
        flush()
        db.tripQueries.finish(ended_ms = endedMs, id = trip)
        tripId = null
        live.clear()
        metricOf.clear()
    }

    // --- internals -------------------------------------------------------------

    /** Pulls everything queued into the in-memory chunks. Never suspends. */
    private fun drain() {
        while (true) {
            val sample = channel.tryReceive().getOrNull() ?: return
            accumulate(sample)
        }
    }

    private fun accumulate(sample: SensorSample) {
        val value = sample.value.asDoubleOrNull ?: return // text signals are not a series
        val secondsIn = (sample.timestampMs - startedMs) / 1000L
        if (secondsIn < 0) return // predates the trip

        val chunkIndex = secondsIn / SECONDS_PER_CHUNK
        if (chunkIndex > currentChunk) currentChunk = chunkIndex

        val slot = (secondsIn % SECONDS_PER_CHUNK).toInt()
        live.getOrPut(chunkIndex) { LinkedHashMap() }
            .getOrPut(sample.signalId) { SignalChunk() }
            .add(slot, value)

        metricOf[sample.signalId] = sample.key
        sampleCount++
        pendingSinceFlush++
    }

    /**
     * One transaction. Every touched chunk BLOB and the trip totals go in together,
     * so a trip's summary can never disagree with the series it was computed from —
     * not even if the process is killed between the two, which on Android it will be.
     */
    private fun flush() {
        if (pendingSinceFlush == 0) return
        val trip = tripId ?: return

        db.transaction {
            for ((chunkIndex, bySignal) in live) {
                for ((signalId, chunk) in bySignal) {
                    val series = chunk.means()
                    val agg = aggregate(series)
                    db.tripSeriesQueries.upsert(
                        trip_id = trip,
                        signal_id = signalId,
                        chunk_index = chunkIndex,
                        t0_s = chunkIndex * SECONDS_PER_CHUNK,
                        n = series.size.toLong(),
                        series = series,
                        min_v = agg?.min,
                        max_v = agg?.max,
                        avg_v = agg?.avg,
                    )
                }
            }
            val t = base + totalsOf(live)
            db.tripQueries.updateTotals(
                distance_m = t.distanceM,
                fuel_ml = t.fuelMl,
                energy_wh = t.energyWh,
                max_speed_kmh = t.maxSpeedKmh,
                idle_ms = t.idleMs,
                sample_count = sampleCount,
                id = trip,
            )
        }
        pendingSinceFlush = 0

        // A chunk older than the current one can receive no further samples. Fold it
        // into the running totals and let it go, so an 8-hour drive does not hold all
        // 48 chunks of every signal in memory.
        val finished = live.keys.filter { it < currentChunk }
        for (chunkIndex in finished) {
            base += totalsOfChunk(live.getValue(chunkIndex))
            live.remove(chunkIndex)
        }
    }

    private fun totalsOf(chunks: Map<Long, Map<String, SignalChunk>>): Totals {
        var t = Totals()
        for (bySignal in chunks.values) t += totalsOfChunk(bySignal)
        return t
    }

    private fun totalsOfChunk(bySignal: Map<String, SignalChunk>): Totals {
        val t = Totals()

        fun seriesFor(metric: SuggestedMetric): FloatArray? =
            bySignal.entries
                .firstOrNull { metricOf[it.key] == MetricKey.Metric(metric) }
                ?.value?.means()

        seriesFor(SuggestedMetric.SPEED)?.let { speed ->
            for (kmh in speed) {
                if (kmh.isNaN()) continue
                // One slot is one second, so metres = (km/h ÷ 3.6) × 1 s.
                t.distanceM += kmh / 3.6
                if (kmh > t.maxSpeedKmh) t.maxSpeedKmh = kmh.toDouble()
                if (kmh == 0f) t.idleMs += 1000
            }
        }

        seriesFor(SuggestedMetric.FUEL_RATE)?.let { rate ->
            for (litresPerHour in rate) {
                if (litresPerHour.isNaN()) continue
                t.fuelMl += litresPerHour * 1000.0 / 3600.0
            }
        }

        val volts = seriesFor(SuggestedMetric.TRACTION_BATTERY_VOLTAGE)
        val amps = seriesFor(SuggestedMetric.TRACTION_BATTERY_CURRENT)
        if (volts != null && amps != null) {
            for (i in 0 until minOf(volts.size, amps.size)) {
                val v = volts[i]
                val a = amps[i]
                if (v.isNaN() || a.isNaN()) continue
                t.energyWh += (v * a) / 3600.0 // watts × 1 s → Wh
            }
        }

        return t
    }
}

/**
 * Chunk aggregates over the non-gap seconds of [series]. Null when the chunk is all
 * gaps. Precomputed at write time so the trip list does not have to unpack a BLOB per
 * row just to draw a sparkline.
 *
 * Public because trip *import* has to compute these the same way recording does. If
 * the two ever diverged, a restored trip would show different statistics from the one
 * that was backed up — and nothing would report an error.
 */
fun aggregate(series: FloatArray): Aggregate? {
    var min = Double.MAX_VALUE
    var max = -Double.MAX_VALUE
    var total = 0.0
    var n = 0
    for (v in series) {
        if (v.isNaN()) continue
        val d = v.toDouble()
        if (d < min) min = d
        if (d > max) max = d
        total += d
        n++
    }
    return if (n == 0) null else Aggregate(min, max, total / n)
}

class Aggregate(val min: Double, val max: Double, val avg: Double)

/**
 * One signal's 10-minute chunk, still being accumulated.
 *
 * Sums and counts, not the means themselves: a second that is still open when a flush
 * happens and then receives more samples must end up with the true mean of all its
 * samples, not a mean of means. Flushes are frequent, and seconds straddle them.
 */
private class SignalChunk {
    private val sum = DoubleArray(SECONDS_PER_CHUNK)
    private val count = IntArray(SECONDS_PER_CHUNK)
    private var lastSlot = -1

    fun add(slot: Int, value: Double) {
        sum[slot] += value
        count[slot]++
        if (slot > lastSlot) lastSlot = slot
    }

    /**
     * One float per second. A second with no sample is NaN — an honest gap. Carrying
     * the previous value forward would be indistinguishable from a real reading, and
     * "the car held exactly 63 km/h for forty seconds" is a lie a chart tells well.
     */
    fun means(): FloatArray = FloatArray(lastSlot + 1) { i ->
        if (count[i] == 0) Float.NaN else (sum[i] / count[i]).toFloat()
    }
}

private class Totals(
    var distanceM: Double = 0.0,
    var fuelMl: Double = 0.0,
    var energyWh: Double = 0.0,
    var maxSpeedKmh: Double = 0.0,
    var idleMs: Long = 0,
) {
    operator fun plus(other: Totals) = Totals(
        distanceM = distanceM + other.distanceM,
        fuelMl = fuelMl + other.fuelMl,
        energyWh = energyWh + other.energyWh,
        maxSpeedKmh = maxOf(maxSpeedKmh, other.maxSpeedKmh),
        idleMs = idleMs + other.idleMs,
    )
}
