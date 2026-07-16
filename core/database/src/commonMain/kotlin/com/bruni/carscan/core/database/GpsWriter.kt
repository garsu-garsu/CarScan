package com.bruni.carscan.core.database

import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One GPS fix, in the shape [GpsWriter] accumulates.
 *
 * This mirrors the `GpsFix` port type in `:core:data` field for field, but is its own
 * type: `:core:database` is a lower module that `:core:data` depends on, so it cannot
 * depend back on `:core:data`'s port. The caller (GpsRecorder, in :composeApp) maps
 * one to the other at the boundary.
 */
data class GpsFixSample(
    val tsMs: Long,
    val lat: Double,
    val lon: Double,
    val altM: Float,
    val speedKmh: Float,
    val bearingDeg: Float,
)

/**
 * Writes a trip's route to disk. Same shape as [SampleWriter] and for the same
 * reasons: [offer] is called from the platform's location callback and must never
 * suspend or block it, flushes are one transaction per interval rather than one per
 * fix, and a second with no fix is stored as a gap (NaN) rather than carried forward.
 *
 * Unlike [SampleWriter], there are no trip totals to maintain here — GpsWriter only
 * ever writes trip_gps, and [stop] does not touch the trip row at all.
 */
class GpsWriter(
    private val db: CarScanDb,
    private val scope: CoroutineScope,
    private val flushIntervalMs: Long = 1_000L,
) {
    private val channel = Channel<GpsFixSample>(
        capacity = 4096,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var job: Job? = null
    private var tripId: String? = null
    private var startedMs: Long = 0

    /** chunkIndex -> the chunk being accumulated. */
    private val live = LinkedHashMap<Long, GpsChunk>()

    private var currentChunk: Long = 0
    private var pendingSinceFlush: Int = 0

    fun start(tripId: String, startedMs: Long) {
        check(this.tripId == null) { "already recording ${this.tripId}" }
        this.tripId = tripId
        this.startedMs = startedMs
        live.clear()
        currentChunk = 0
        pendingSinceFlush = 0
        job = scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                drain()
                flush()
            }
        }
    }

    /** Never suspends. Drops the oldest queued fix rather than block the caller. */
    fun offer(fix: GpsFixSample) {
        if (tripId == null) return
        channel.trySend(fix)
    }

    /** Drains what is queued and writes the final chunks. Does not touch the trip row. */
    suspend fun stop() {
        tripId ?: return
        job?.cancel()
        job = null
        drain()
        flush()
        tripId = null
        live.clear()
    }

    // --- internals -------------------------------------------------------------

    private fun drain() {
        while (true) {
            val fix = channel.tryReceive().getOrNull() ?: return
            accumulate(fix)
        }
    }

    private fun accumulate(fix: GpsFixSample) {
        val secondsIn = (fix.tsMs - startedMs) / 1000L
        if (secondsIn < 0) return // predates the trip

        val chunkIndex = secondsIn / SECONDS_PER_CHUNK
        if (chunkIndex > currentChunk) currentChunk = chunkIndex

        val slot = (secondsIn % SECONDS_PER_CHUNK).toInt()
        live.getOrPut(chunkIndex) { GpsChunk() }.add(slot, fix)
        pendingSinceFlush++
    }

    /** One transaction. Every touched chunk BLOB goes in together. */
    private fun flush() {
        if (pendingSinceFlush == 0) return
        val trip = tripId ?: return

        db.transaction {
            for ((chunkIndex, chunk) in live) {
                db.tripGpsQueries.upsert(
                    trip_id = trip,
                    chunk_index = chunkIndex,
                    t0_s = chunkIndex * SECONDS_PER_CHUNK,
                    n = chunk.size.toLong(),
                    lat = chunk.lat(),
                    lon = chunk.lon(),
                    alt = chunk.alt(),
                    speed = chunk.speed(),
                    bearing = chunk.bearing(),
                )
            }
        }
        pendingSinceFlush = 0

        // A chunk older than the current one can receive no further fixes. Let it go,
        // so a long drive does not hold every ten-minute chunk of the route in memory.
        val finished = live.keys.filter { it < currentChunk }
        for (chunkIndex in finished) live.remove(chunkIndex)
    }
}

/**
 * One 10-minute chunk of a route, still being accumulated.
 *
 * Sums and counts, not the means themselves, for the same reason as [SampleWriter]'s
 * `SignalChunk`: a second still open at a flush must end up with the true mean of all
 * its fixes, not a mean of means.
 */
private class GpsChunk {
    private val latSum = DoubleArray(SECONDS_PER_CHUNK)
    private val lonSum = DoubleArray(SECONDS_PER_CHUNK)
    private val altSum = DoubleArray(SECONDS_PER_CHUNK)
    private val speedSum = DoubleArray(SECONDS_PER_CHUNK)
    private val bearingSum = DoubleArray(SECONDS_PER_CHUNK)
    private val count = IntArray(SECONDS_PER_CHUNK)
    private var lastSlot = -1

    val size: Int get() = lastSlot + 1

    fun add(slot: Int, fix: GpsFixSample) {
        latSum[slot] += fix.lat
        lonSum[slot] += fix.lon
        altSum[slot] += fix.altM
        speedSum[slot] += fix.speedKmh
        bearingSum[slot] += fix.bearingDeg
        count[slot]++
        if (slot > lastSlot) lastSlot = slot
    }

    /** A second with no fix is NaN — an honest gap, not a value carried forward. */
    fun lat(): DoubleArray = DoubleArray(size) { i -> if (count[i] == 0) Double.NaN else latSum[i] / count[i] }
    fun lon(): DoubleArray = DoubleArray(size) { i -> if (count[i] == 0) Double.NaN else lonSum[i] / count[i] }
    fun alt(): FloatArray =
        FloatArray(size) { i -> if (count[i] == 0) Float.NaN else (altSum[i] / count[i]).toFloat() }
    fun speed(): FloatArray =
        FloatArray(size) { i -> if (count[i] == 0) Float.NaN else (speedSum[i] / count[i]).toFloat() }
    fun bearing(): FloatArray =
        FloatArray(size) { i -> if (count[i] == 0) Float.NaN else (bearingSum[i] / count[i]).toFloat() }
}
