package com.bruni.carscan.core.data

import com.bruni.carscan.core.database.SampleWriter
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
    /** Null for a GPS-only trip (source = "GPS") started with no vehicle to attach to. */
    val vehicleId: String?,
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

enum class HarshEventType { HARSH_ACCEL, HARSH_BRAKE, HARSH_START, HARSH_STOP, HARSH_CORNER }

/** One harsh-driving maneuver HarshEventDetector caught, at its peak severity. */
data class TripEvent(
    val id: String,
    val tripId: String,
    val tsMs: Long,
    val type: HarshEventType,
    val severityMs2: Double,
    val lat: Double,
    val lon: Double,
)

/**
 * One signal across a whole trip, stitched back out of its chunks.
 *
 * [values] is one entry per second since the trip started, so `values[t]` *is* the
 * reading at second `t` — scrubbing a playback timeline is an array index, not a
 * query. NaN means the car reported nothing in that second.
 */
class SignalSeries(val signalId: String, val values: FloatArray)

/** One fix on a trip's route — just enough for a map polyline point. */
data class GpsPoint(val lat: Double, val lon: Double)

interface TripRepository {
    val isRecording: Boolean

    /** The trip currently being recorded, if any. Null when nothing is recording. */
    val activeTrip: StateFlow<ActiveTrip?>

    /**
     * Inserts a trip and starts recording into it. Returns its (UUID) id.
     *
     * [vehicleId] is null for a GPS-only trip DrivingDetector starts with no adapter connected —
     * there is no vehicle to attach it to. [source] tags who recorded it ("OBD" or "GPS"); it
     * defaults to "OBD" because every existing caller before DrivingDetector is the OBD recorder.
     */
    suspend fun start(vehicleId: String?, startedMs: Long, source: String = "OBD"): String

    /** Hands a sample to the writer. Never suspends — the poller must not block on disk. */
    fun offer(sample: SensorSample)

    suspend fun stop(endedMs: Long)

    /**
     * Closes out every trip a previous process left open, and must be called once at startup,
     * before anything can open a new one.
     *
     * `ended_ms` is only ever written by [stop], in the process that called [start]. Kill that
     * process mid-drive — the OS reclaiming memory, a crash, the user swiping the app away — and
     * the row stays open forever: nothing on the next launch was going to finish it, and the trip
     * list showed it as "Recording…" for a trip nothing was recording.
     *
     * What it does with each one is decided by whether anything was actually recorded, **not** by
     * distance: a GPS-only trip has no OBD speed samples, so its `distance_m` is 0.0 however far
     * it went, and discarding on distance would delete every auto-detected drive in the history.
     *
     * - Nothing recorded at all — no route, no samples — is discarded. There is nothing to show,
     *   and this is the false start (one spurious fix at speed) that the phantom rows came from.
     * - Anything recorded is **finished, never deleted**, at the last second it has data for.
     *   Losing a real drive because the app was killed would be far worse than showing a short one.
     */
    suspend fun recoverStranded()

    suspend fun trips(vehicleId: String): List<TripSummary>

    /** Every trip, across every vehicle, newest first — the trip-list screen's filter is by source, not by vehicle. */
    suspend fun allTrips(): List<TripSummary>
    suspend fun summary(tripId: String): TripSummary?
    suspend fun signalIds(tripId: String): List<String>

    /** The whole trip for one signal, for playback. Null if it was never recorded. */
    suspend fun series(tripId: String, signalId: String): SignalSeries?

    suspend fun setStartLocation(tripId: String, lat: Double, lon: Double, address: String?)
    suspend fun setEndLocation(tripId: String, lat: Double, lon: Double, address: String?)

    /**
     * Restores a trip's summary row from a backup. **Idempotent**: importing the same trip
     * twice leaves one trip, not two.
     *
     * The trip's samples and route are restored separately, chunk by chunk, by `BackupService`
     * — a whole-trip [SignalSeries] here would mean holding every second of a long drive in
     * memory just to slice it back into the chunks it was already stored as.
     */
    suspend fun import(trip: TripSummary)

    suspend fun delete(tripId: String)

    /** Stores one harsh-driving maneuver, already at its debounced peak severity. */
    suspend fun recordEvent(event: TripEvent)

    /** Every harsh-driving event for a trip, in ts order — the order a marker list draws in. */
    suspend fun events(tripId: String): List<TripEvent>

    /**
     * The trip's route, one point per second that had a fix — trip_gps's NaN holes (see
     * GpsWriter) are skipped rather than kept as gaps, since a map polyline has no use for one.
     * Empty if no GPS was ever recorded for this trip.
     */
    suspend fun track(tripId: String): List<GpsPoint>
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

    override suspend fun start(vehicleId: String?, startedMs: Long, source: String): String {
        val id = newId()
        db.tripQueries.insertOrIgnore(
            id = id, vehicle_id = vehicleId, started_ms = startedMs, ended_ms = null,
            distance_m = 0.0, fuel_ml = 0.0, energy_wh = 0.0,
            max_speed_kmh = 0.0, idle_ms = 0, sample_count = 0, source = source,
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

    override suspend fun recoverStranded() {
        // The live trip, if this process already opened one, is the one row that is legitimately
        // open — everything else in selectUnfinished belongs to a process that is gone.
        val live = _activeTrip.value?.id
        for (trip in db.tripQueries.selectUnfinished().executeAsList()) {
            if (trip.id == live) continue
            val lastSecond = lastRecordedSecond(trip.id)
            if (lastSecond == null) db.tripQueries.deleteById(trip.id)
            else db.tripQueries.finish(ended_ms = trip.started_ms + lastSecond * 1_000, id = trip.id)
        }
    }

    /**
     * Seconds-since-start of the last thing recorded for a trip, across both stores; null when
     * neither has a single chunk, which is the "nothing was recorded" that [recoverStranded]
     * discards on.
     */
    private fun lastRecordedSecond(tripId: String): Long? = listOfNotNull(
        db.tripGpsQueries.lastSecond(tripId).executeAsOneOrNull(),
        db.tripSeriesQueries.lastSecond(tripId).executeAsOneOrNull(),
    ).maxOrNull()

    override suspend fun setStartLocation(tripId: String, lat: Double, lon: Double, address: String?) {
        db.tripQueries.setStartLocation(start_lat = lat, start_lon = lon, start_address = address, id = tripId)
    }

    override suspend fun setEndLocation(tripId: String, lat: Double, lon: Double, address: String?) {
        db.tripQueries.setEndLocation(end_lat = lat, end_lon = lon, end_address = address, id = tripId)
    }

    override suspend fun trips(vehicleId: String): List<TripSummary> =
        db.tripQueries.selectForVehicle(vehicleId).executeAsList().map(Trip::toSummary)

    override suspend fun allTrips(): List<TripSummary> =
        db.tripQueries.selectAll().executeAsList().map(Trip::toSummary)

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
     * One transaction, every write keyed on the trip's UUID. `INSERT OR IGNORE` and not
     * OR REPLACE, because REPLACE deletes the conflicting row first and the ON DELETE CASCADE
     * would take the trip's already-restored series and route with it — the exact opposite of
     * what a second import must do.
     */
    override suspend fun import(trip: TripSummary) {
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
        }
    }

    /** The ON DELETE CASCADE takes trip_series and trip_gps with it. */
    override suspend fun delete(tripId: String) {
        db.tripQueries.deleteById(tripId)
    }

    override suspend fun recordEvent(event: TripEvent) {
        db.tripEventQueries.insert(
            id = event.id, trip_id = event.tripId, ts_ms = event.tsMs,
            type = event.type.name, severity = event.severityMs2,
            lat = event.lat, lon = event.lon,
        )
    }

    override suspend fun events(tripId: String): List<TripEvent> =
        db.tripEventQueries.selectForTrip(tripId).executeAsList().map {
            TripEvent(
                id = it.id, tripId = it.trip_id, tsMs = it.ts_ms,
                type = HarshEventType.valueOf(it.type), severityMs2 = it.severity,
                lat = it.lat, lon = it.lon,
            )
        }

    override suspend fun track(tripId: String): List<GpsPoint> {
        val points = mutableListOf<GpsPoint>()
        for (chunk in db.tripGpsQueries.selectTrip(tripId).executeAsList()) {
            for (i in 0 until chunk.n.toInt()) {
                val lat = chunk.lat[i]
                val lon = chunk.lon[i]
                if (!lat.isNaN() && !lon.isNaN()) points += GpsPoint(lat, lon)
            }
        }
        return points
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
