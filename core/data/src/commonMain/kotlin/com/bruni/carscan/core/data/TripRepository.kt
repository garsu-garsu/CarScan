package com.bruni.carscan.core.data

import com.bruni.carscan.core.database.SECONDS_PER_CHUNK
import com.bruni.carscan.core.database.SampleWriter
import com.bruni.carscan.core.database.aggregate
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.db.CarScanDb
import com.bruni.carscan.db.Trip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A trip's summary. All values are in native/SI units: metres, millilitres, watt-hours, km/h. */
data class TripSummary(
    val id: String,
    val vehicleId: String,
    val startedMs: Long,
    val endedMs: Long?,
    val distanceM: Double,
    val fuelMl: Double,
    val energyWh: Double,
    val maxSpeedKmh: Double,
    val idleMs: Long,
    val sampleCount: Long,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    val startAddress: String? = null,
    val endAddress: String? = null,
    val source: String = "OBD",
)

/** The trip currently being recorded, if any. */
data class ActiveTrip(val id: String, val startedMs: Long)

/**
 * One signal across a whole trip, stitched back out of its chunks.
 *
 * [values] is one entry per second since the trip started, so `values[t]` *is* the
 * reading at second `t` — scrubbing a playback timeline is an array index, not a
 * query. NaN means the car reported nothing in that second.
 */
class SignalSeries(val signalId: String, val values: FloatArray)

interface TripRepository {
    val isRecording: Boolean

    /** The trip currently being recorded, if any. Null when nothing is recording. */
    val activeTrip: StateFlow<ActiveTrip?>

    /** Inserts a trip and starts recording into it. Returns its (UUID) id. */
    suspend fun start(vehicleId: String, startedMs: Long): String

    /** Hands a sample to the writer. Never suspends — the poller must not block on disk. */
    fun offer(sample: SensorSample)

    suspend fun stop(endedMs: Long)

    suspend fun trips(vehicleId: String): List<TripSummary>
    suspend fun summary(tripId: String): TripSummary?
    suspend fun signalIds(tripId: String): List<String>

    /** The whole trip for one signal, for playback. Null if it was never recorded. */
    suspend fun series(tripId: String, signalId: String): SignalSeries?

    suspend fun setStartLocation(tripId: String, lat: Double, lon: Double, address: String?)
    suspend fun setEndLocation(tripId: String, lat: Double, lon: Double, address: String?)

    /**
     * Restores a trip from a backup. **Idempotent**: importing the same trip twice
     * leaves one trip, not two.
     */
    suspend fun import(trip: TripSummary, series: List<SignalSeries>)

    suspend fun delete(tripId: String)
}

class DefaultTripRepository(
    private val db: CarScanDb,
    scope: CoroutineScope,
    private val newId: () -> String = { newUuid() },
    flushIntervalMs: Long = 1_000L,
) : TripRepository {

    private val writer = SampleWriter(db, scope, flushIntervalMs)

    private val _activeTrip = MutableStateFlow<ActiveTrip?>(null)
    override val activeTrip: StateFlow<ActiveTrip?> = _activeTrip.asStateFlow()

    override val isRecording: Boolean get() = writer.isRecording

    override suspend fun start(vehicleId: String, startedMs: Long): String {
        val id = newId()
        db.tripQueries.insertOrIgnore(
            id = id, vehicle_id = vehicleId, started_ms = startedMs, ended_ms = null,
            distance_m = 0.0, fuel_ml = 0.0, energy_wh = 0.0,
            max_speed_kmh = 0.0, idle_ms = 0, sample_count = 0, source = "OBD",
        )
        writer.start(id, startedMs)
        _activeTrip.value = ActiveTrip(id, startedMs)
        return id
    }

    override fun offer(sample: SensorSample) = writer.offer(sample)

    override suspend fun stop(endedMs: Long) {
        writer.stop(endedMs)
        _activeTrip.value = null
    }

    override suspend fun setStartLocation(tripId: String, lat: Double, lon: Double, address: String?) {
        db.tripQueries.setStartLocation(start_lat = lat, start_lon = lon, start_address = address, id = tripId)
    }

    override suspend fun setEndLocation(tripId: String, lat: Double, lon: Double, address: String?) {
        db.tripQueries.setEndLocation(end_lat = lat, end_lon = lon, end_address = address, id = tripId)
    }

    override suspend fun trips(vehicleId: String): List<TripSummary> =
        db.tripQueries.selectForVehicle(vehicleId).executeAsList().map(Trip::toSummary)

    override suspend fun summary(tripId: String): TripSummary? =
        db.tripQueries.selectById(tripId).executeAsOneOrNull()?.toSummary()

    override suspend fun signalIds(tripId: String): List<String> =
        db.tripSeriesQueries.selectSignalIds(tripId).executeAsList()

    override suspend fun series(tripId: String, signalId: String): SignalSeries? {
        val chunks = db.tripSeriesQueries.selectSignal(tripId, signalId).executeAsList()
        if (chunks.isEmpty()) return null

        val last = chunks.last()
        val seconds = (last.t0_s + last.n).toInt()
        // NaN, not 0.0: a chunk the car never answered for is a hole in the trip, and a
        // hole must not read back as "stationary".
        val values = FloatArray(seconds) { Float.NaN }
        for (chunk in chunks) chunk.series.copyInto(values, chunk.t0_s.toInt())
        return SignalSeries(signalId, values)
    }

    /**
     * All of it in one transaction, and every write keyed on the trip's UUID:
     * `INSERT OR IGNORE` for the trip (OR REPLACE would cascade-delete the series we
     * are about to write) and `INSERT OR REPLACE` for the chunks, which nothing
     * references. Import the same backup twice and the second pass overwrites the
     * first with identical bytes.
     */
    override suspend fun import(trip: TripSummary, series: List<SignalSeries>) {
        db.transaction {
            db.tripQueries.insertOrIgnore(
                id = trip.id, vehicle_id = trip.vehicleId, started_ms = trip.startedMs,
                ended_ms = trip.endedMs, distance_m = trip.distanceM, fuel_ml = trip.fuelMl,
                energy_wh = trip.energyWh, max_speed_kmh = trip.maxSpeedKmh,
                idle_ms = trip.idleMs, sample_count = trip.sampleCount, source = trip.source,
            )
            // The row may already have existed, in which case OR IGNORE left it alone.
            db.tripQueries.updateTotals(
                distance_m = trip.distanceM, fuel_ml = trip.fuelMl, energy_wh = trip.energyWh,
                max_speed_kmh = trip.maxSpeedKmh, idle_ms = trip.idleMs,
                sample_count = trip.sampleCount, id = trip.id,
            )
            db.tripQueries.finish(ended_ms = trip.endedMs, id = trip.id)
            if (trip.startLat != null && trip.startLon != null) {
                db.tripQueries.setStartLocation(
                    start_lat = trip.startLat, start_lon = trip.startLon,
                    start_address = trip.startAddress, id = trip.id,
                )
            }
            if (trip.endLat != null && trip.endLon != null) {
                db.tripQueries.setEndLocation(
                    end_lat = trip.endLat, end_lon = trip.endLon,
                    end_address = trip.endAddress, id = trip.id,
                )
            }

            for (signal in series) {
                var offset = 0
                var chunkIndex = 0L
                while (offset < signal.values.size) {
                    val end = minOf(offset + SECONDS_PER_CHUNK, signal.values.size)
                    val slice = signal.values.copyOfRange(offset, end)
                    // Same aggregate function recording uses, so a restored trip's
                    // statistics cannot silently differ from the backed-up one's.
                    val agg = aggregate(slice)
                    db.tripSeriesQueries.upsert(
                        trip_id = trip.id,
                        signal_id = signal.signalId,
                        chunk_index = chunkIndex,
                        t0_s = chunkIndex * SECONDS_PER_CHUNK,
                        n = slice.size.toLong(),
                        series = slice,
                        min_v = agg?.min, max_v = agg?.max, avg_v = agg?.avg,
                    )
                    offset = end
                    chunkIndex++
                }
            }
        }
    }

    /** The ON DELETE CASCADE takes trip_series and trip_gps with it. */
    override suspend fun delete(tripId: String) {
        db.tripQueries.deleteById(tripId)
    }
}

private fun Trip.toSummary() = TripSummary(
    id = id,
    vehicleId = vehicle_id,
    startedMs = started_ms,
    endedMs = ended_ms,
    distanceM = distance_m,
    fuelMl = fuel_ml,
    energyWh = energy_wh,
    maxSpeedKmh = max_speed_kmh,
    idleMs = idle_ms,
    sampleCount = sample_count,
    startLat = start_lat,
    startLon = start_lon,
    endLat = end_lat,
    endLon = end_lon,
    startAddress = start_address,
    endAddress = end_address,
    source = source,
)
