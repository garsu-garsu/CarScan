package com.bruni.carscan.core.data.backup

import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.BookmarkRepository
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.data.TripSummary
import com.bruni.carscan.core.data.decodeMetricKey
import com.bruni.carscan.core.data.encodeMetricKey
import com.bruni.carscan.core.database.DoubleArrayColumnAdapter
import com.bruni.carscan.core.database.FloatArrayColumnAdapter
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** The port the settings screen drives. See [DefaultBackupService] for what the guarantees are. */
interface BackupService {
    suspend fun export(sink: BackupSink, password: String): BackupOutcome
    suspend fun import(source: BackupSource, password: String): BackupOutcome
}

/**
 * Writes and restores the encrypted backup file.
 *
 * **Restoring merges; it never wipes.** Every write below is an insert keyed on the row's own
 * UUID, so a phone that already holds drives keeps them and gains the file's — and importing the
 * same file twice is the same as importing it once. That is the whole reason trip, vehicle and
 * layout ids are UUIDs rather than autoincrement integers.
 *
 * **Nothing loads a whole trip.** Export walks `trip_series` and `trip_gps` one 10-minute chunk
 * at a time (`selectChunkKeys`/`selectChunkIndexes` name the chunks without touching a BLOB), and
 * each chunk becomes one encrypted frame that goes straight out to the sink. Peak memory is one
 * chunk — a few tens of kilobytes — no matter how long the drive was.
 *
 * There is deliberately **no transaction around the whole restore**. A multi-megabyte import
 * inside one transaction is a WAL the size of the backup, and the atomicity would buy nothing:
 * every write is additive and idempotent, so a restore interrupted half way through has damaged
 * nothing and re-running it finishes the job.
 */
class DefaultBackupService(
    private val db: CarScanDb,
    private val trips: TripRepository,
    private val settings: SettingsRepository,
    private val bookmarks: BookmarkRepository,
) : BackupService {

    /**
     * Encrypts everything into [sink] under [password].
     *
     * Order matters on the way back in: vehicles precede the trips, layouts and fault codes that
     * reference them, and each trip precedes its own chunks and events.
     */
    override suspend fun export(sink: BackupSink, password: String): BackupOutcome = withContext(Dispatchers.IO) {
        if (password.isEmpty()) return@withContext BackupOutcome.FAILED
        try {
            val writer = BackupWriter(sink, password)

            val current = settings.settings.first()
            writer.write(
                SettingsRecord(
                    recordTrips = current.recordTrips,
                    units = current.units.encode(),
                    keepScreenOn = current.keepScreenOn,
                    activeVehicleId = current.activeVehicleId,
                    themeMode = current.themeMode.name,
                    gaugeStyle = current.gaugeStyle,
                    autoReconnect = current.autoReconnect,
                    acquisitionSource = current.acquisitionSource.name,
                    autoDriveDetectSpeedKmh = current.autoDriveDetectSpeedKmh,
                    backgroundTracking = current.backgroundTracking,
                ),
            )
            writer.write(BookmarksRecord(bookmarks.bookmarks.first().map(::encodeMetricKey)))

            val vehicles = db.vehicleQueries.selectAll().executeAsList()
            for (vehicle in vehicles) {
                writer.write(
                    VehicleRecord(
                        id = vehicle.id, vin = vehicle.vin, make = vehicle.make, model = vehicle.model,
                        modelYear = vehicle.model_year, obdbRepo = vehicle.obdb_repo,
                        displayName = vehicle.display_name, protocolNum = vehicle.protocol_num,
                        lastConnectedMs = vehicle.last_connected_ms, createdMs = vehicle.created_ms,
                    ),
                )
            }

            for (layout in db.dashboardLayoutQueries.selectAll().executeAsList()) {
                writer.write(
                    DashboardLayoutRecord(
                        id = layout.id, vehicleId = layout.vehicle_id, name = layout.name,
                        isActive = layout.is_active != 0L, layoutJson = layout.layout_json,
                    ),
                )
            }

            for (vehicle in vehicles) {
                for (dtc in db.dtcEventQueries.selectAllForVehicle(vehicle.id).executeAsList()) {
                    writer.write(
                        DtcRecord(
                            id = dtc.id, vehicleId = dtc.vehicle_id, code = dtc.code, status = dtc.status,
                            ecu = dtc.ecu, firstSeenMs = dtc.first_seen_ms, lastSeenMs = dtc.last_seen_ms,
                            clearedMs = dtc.cleared_ms, freezeFrame = dtc.freeze_frame,
                        ),
                    )
                }
            }

            for (trip in db.tripQueries.selectAll().executeAsList()) {
                writer.write(
                    TripRecord(
                        id = trip.id, vehicleId = trip.vehicle_id, startedMs = trip.started_ms,
                        endedMs = trip.ended_ms, distanceM = trip.distance_m, fuelMl = trip.fuel_ml,
                        energyWh = trip.energy_wh, maxSpeedKmh = trip.max_speed_kmh, idleMs = trip.idle_ms,
                        sampleCount = trip.sample_count, startLat = trip.start_lat, startLon = trip.start_lon,
                        endLat = trip.end_lat, endLon = trip.end_lon, startAddress = trip.start_address,
                        endAddress = trip.end_address, source = trip.source,
                    ),
                )

                // One chunk in memory at a time — see the class KDoc.
                for (key in db.tripSeriesQueries.selectChunkKeys(trip.id).executeAsList()) {
                    val chunk = db.tripSeriesQueries
                        .selectChunk(trip.id, key.signal_id, key.chunk_index)
                        .executeAsOne()
                    writer.write(
                        SeriesChunkRecord(
                            tripId = chunk.trip_id, signalId = chunk.signal_id,
                            chunkIndex = chunk.chunk_index, t0S = chunk.t0_s, n = chunk.n,
                            series = base64Encode(FloatArrayColumnAdapter.encode(chunk.series)),
                            minV = chunk.min_v, maxV = chunk.max_v, avgV = chunk.avg_v,
                        ),
                    )
                }

                for (index in db.tripGpsQueries.selectChunkIndexes(trip.id).executeAsList()) {
                    val chunk = db.tripGpsQueries.selectChunk(trip.id, index).executeAsOne()
                    writer.write(
                        GpsChunkRecord(
                            tripId = chunk.trip_id, chunkIndex = chunk.chunk_index,
                            t0S = chunk.t0_s, n = chunk.n,
                            lat = base64Encode(DoubleArrayColumnAdapter.encode(chunk.lat)),
                            lon = base64Encode(DoubleArrayColumnAdapter.encode(chunk.lon)),
                            alt = base64Encode(FloatArrayColumnAdapter.encode(chunk.alt)),
                            speed = base64Encode(FloatArrayColumnAdapter.encode(chunk.speed)),
                            bearing = base64Encode(FloatArrayColumnAdapter.encode(chunk.bearing)),
                        ),
                    )
                }

                for (event in db.tripEventQueries.selectForTrip(trip.id).executeAsList()) {
                    writer.write(
                        TripEventRecord(
                            id = event.id, tripId = event.trip_id, tsMs = event.ts_ms, type = event.type,
                            severity = event.severity, lat = event.lat, lon = event.lon,
                        ),
                    )
                }
            }
            BackupOutcome.OK
        } catch (_: Exception) {
            // Storage that filled up, a revoked SAF permission, a card pulled out. There is
            // nothing to tell the user beyond "it did not work" and nothing was modified.
            BackupOutcome.FAILED
        }
    }

    /** Merges [source] into this device's data. See the class KDoc for what "merge" guarantees. */
    override suspend fun import(source: BackupSource, password: String): BackupOutcome = withContext(Dispatchers.IO) {
        if (password.isEmpty()) return@withContext BackupOutcome.WRONG_PASSWORD
        try {
            val reader = BackupReader(source, password)
            val vehicleIds = mutableMapOf<String, String>()
            var settingsRecord: SettingsRecord? = null
            var bookmarksRecord: BookmarksRecord? = null

            while (true) {
                when (val record = reader.next() ?: break) {
                    // Held back: DataStore writes suspend, and the vehicle id remapping the
                    // active-vehicle preference needs is not complete until the last vehicle is in.
                    is SettingsRecord -> settingsRecord = record
                    is BookmarksRecord -> bookmarksRecord = record
                    is VehicleRecord -> restoreVehicle(record, vehicleIds)
                    is DashboardLayoutRecord -> restoreLayout(record, vehicleIds)
                    is DtcRecord -> restoreDtc(record, vehicleIds)
                    is TripRecord -> restoreTrip(record, vehicleIds)
                    is SeriesChunkRecord -> restoreSeriesChunk(record)
                    is GpsChunkRecord -> restoreGpsChunk(record)
                    is TripEventRecord -> restoreEvent(record)
                }
            }

            settingsRecord?.let { restoreSettings(it, vehicleIds) }
            bookmarksRecord?.let { restoreBookmarks(it) }
            BackupOutcome.OK
        } catch (e: BackupFormatException) {
            e.outcome
        } catch (_: Exception) {
            BackupOutcome.FAILED
        }
    }

    /**
     * The same car, paired on both phones, is one car.
     *
     * `vehicle.vin` is uniquely indexed, so inserting the backup's row under its own UUID would
     * be silently ignored — and then every trip pointing at that UUID would fail the foreign key
     * and take the whole restore down. Mapping the backup's id onto the local one instead is what
     * lets a backup merge onto a phone that is already in use.
     */
    private fun restoreVehicle(record: VehicleRecord, vehicleIds: MutableMap<String, String>) {
        val existing = record.vin?.let { db.vehicleQueries.selectByVin(it).executeAsOneOrNull() }
        if (existing != null && existing.id != record.id) {
            vehicleIds[record.id] = existing.id
            return
        }
        // INSERT OR IGNORE, never an update: a vehicle the user has since renamed on this phone
        // keeps the name they gave it.
        db.vehicleQueries.insertOrIgnore(
            id = record.id, vin = record.vin, make = record.make, model = record.model,
            model_year = record.modelYear, obdb_repo = record.obdbRepo,
            display_name = record.displayName, protocol_num = record.protocolNum,
            last_connected_ms = record.lastConnectedMs, created_ms = record.createdMs,
        )
    }

    private suspend fun restoreTrip(record: TripRecord, vehicleIds: Map<String, String>) {
        trips.import(
            TripSummary(
                id = record.id,
                vehicleId = record.vehicleId?.let { vehicleIds[it] ?: it },
                startedMs = record.startedMs, endedMs = record.endedMs, distanceM = record.distanceM,
                fuelMl = record.fuelMl, energyWh = record.energyWh, maxSpeedKmh = record.maxSpeedKmh,
                idleMs = record.idleMs, sampleCount = record.sampleCount, startLat = record.startLat,
                startLon = record.startLon, endLat = record.endLat, endLon = record.endLon,
                startAddress = record.startAddress, endAddress = record.endAddress, source = record.source,
            ),
        )
    }

    private fun restoreSeriesChunk(record: SeriesChunkRecord) {
        db.tripSeriesQueries.upsert(
            trip_id = record.tripId, signal_id = record.signalId, chunk_index = record.chunkIndex,
            t0_s = record.t0S, n = record.n,
            series = FloatArrayColumnAdapter.decode(base64Decode(record.series)),
            min_v = record.minV, max_v = record.maxV, avg_v = record.avgV,
        )
    }

    private fun restoreGpsChunk(record: GpsChunkRecord) {
        db.tripGpsQueries.upsert(
            trip_id = record.tripId, chunk_index = record.chunkIndex, t0_s = record.t0S, n = record.n,
            lat = DoubleArrayColumnAdapter.decode(base64Decode(record.lat)),
            lon = DoubleArrayColumnAdapter.decode(base64Decode(record.lon)),
            alt = FloatArrayColumnAdapter.decode(base64Decode(record.alt)),
            speed = FloatArrayColumnAdapter.decode(base64Decode(record.speed)),
            bearing = FloatArrayColumnAdapter.decode(base64Decode(record.bearing)),
        )
    }

    private fun restoreEvent(record: TripEventRecord) {
        db.tripEventQueries.insertOrIgnore(
            id = record.id, trip_id = record.tripId, ts_ms = record.tsMs, type = record.type,
            severity = record.severity, lat = record.lat, lon = record.lon,
        )
    }

    private fun restoreDtc(record: DtcRecord, vehicleIds: Map<String, String>) {
        db.dtcEventQueries.insertOrIgnore(
            id = record.id,
            vehicle_id = vehicleIds[record.vehicleId] ?: record.vehicleId,
            code = record.code, status = record.status, ecu = record.ecu,
            first_seen_ms = record.firstSeenMs, last_seen_ms = record.lastSeenMs,
            cleared_ms = record.clearedMs, freeze_frame = record.freezeFrame,
        )
    }

    private fun restoreLayout(record: DashboardLayoutRecord, vehicleIds: Map<String, String>) {
        // OR IGNORE only. A layout the user has since rearranged on this phone is theirs.
        db.dashboardLayoutQueries.insertOrIgnore(
            id = record.id,
            vehicle_id = record.vehicleId?.let { vehicleIds[it] ?: it },
            name = record.name,
            is_active = if (record.isActive) 1L else 0L,
            layout_json = record.layoutJson,
        )
    }

    /**
     * Preferences are singular values, so restoring them overwrites rather than merges — that is
     * what "restore my settings" means. An enum name this build does not know is skipped rather
     * than thrown, the same defensive read `DefaultSettingsRepository` does.
     */
    private suspend fun restoreSettings(record: SettingsRecord, vehicleIds: Map<String, String>) {
        settings.setRecordTrips(record.recordTrips)
        settings.setUnits(UnitPreferences.decode(record.units))
        settings.setKeepScreenOn(record.keepScreenOn)
        settings.setActiveVehicleId(record.activeVehicleId?.let { vehicleIds[it] ?: it })
        settings.setGaugeStyle(record.gaugeStyle)
        settings.setAutoReconnect(record.autoReconnect)
        settings.setAutoDriveDetectSpeedKmh(record.autoDriveDetectSpeedKmh)
        settings.setBackgroundTracking(record.backgroundTracking)
        ThemeMode.entries.firstOrNull { it.name == record.themeMode }?.let { settings.setThemeMode(it) }
        AcquisitionSource.entries.firstOrNull { it.name == record.acquisitionSource }
            ?.let { settings.setAcquisitionSource(it) }
    }

    /** Merged, not replaced — `toggle` is the only writer, and a star already set stays set. */
    private suspend fun restoreBookmarks(record: BookmarksRecord) {
        for (token in record.keys) {
            val key = decodeMetricKey(token) ?: continue
            if (!bookmarks.isBookmarked(key)) bookmarks.toggle(key)
        }
    }
}
