package com.bruni.carscan.core.data.backup

import app.cash.sqldelight.db.SqlDriver
import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.BookmarkRepository
import com.bruni.carscan.core.data.DefaultBookmarkRepository
import com.bruni.carscan.core.data.DefaultSettingsRepository
import com.bruni.carscan.core.data.DefaultTripRepository
import com.bruni.carscan.core.data.InMemoryPreferencesDataStore
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.data.TripSummary
import com.bruni.carscan.core.data.createTestDriver
import com.bruni.carscan.core.database.createDatabase
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSWORD = "내백업암호1234"
private const val TRIP = "aaaaaaaa-1111-4111-8111-aaaaaaaaaaaa"
private const val OTHER_TRIP = "bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb"
private const val CAR = "cccccccc-3333-4333-8333-cccccccccccc"
private const val VIN = "KNAB1234567890123"

/** One device: its database, its preferences, and the service over both. */
private class Device {
    val driver: SqlDriver = createTestDriver()
    val db: CarScanDb = createDatabase(driver)
    val store = InMemoryPreferencesDataStore()
    val settings: SettingsRepository = DefaultSettingsRepository(store)
    val bookmarks: BookmarkRepository = DefaultBookmarkRepository(store)

    fun service(scope: CoroutineScope) =
        DefaultBackupService(db, DefaultTripRepository(db, scope), settings, bookmarks)

    fun counts() = listOf(
        db.vehicleQueries.countAll().executeAsOne(),
        db.tripQueries.countAll().executeAsOne(),
        db.tripSeriesQueries.countAll().executeAsOne(),
        db.tripGpsQueries.countAll().executeAsOne(),
        db.tripEventQueries.countForTrip(TRIP).executeAsOne(),
        db.dtcEventQueries.countAll().executeAsOne(),
        db.dashboardLayoutQueries.countAll().executeAsOne(),
    )
}

/**
 * A drive long enough to span four 10-minute chunks, with a hole in it, plus a route, harsh-event
 * markers, a fault code, a dashboard and a full set of preferences. Everything a restore has to
 * bring back.
 */
private suspend fun Device.seed(scope: CoroutineScope) {
    db.vehicleQueries.insertOrIgnore(
        id = CAR, vin = VIN, make = "Kia", model = "EV6", model_year = 2023,
        obdb_repo = "Kia-EV6", display_name = "내 EV6", protocol_num = 6,
        last_connected_ms = 999, created_ms = 100,
    )
    DefaultTripRepository(db, scope).import(
        TripSummary(
            id = TRIP, vehicleId = CAR, startedMs = 1_000, endedMs = 2_400_000,
            distanceM = 31_400.0, fuelMl = 0.0, energyWh = 5_200.0, maxSpeedKmh = 104.0,
            idleMs = 60_000, sampleCount = 2_000, startLat = 37.5665, startLon = 126.9780,
            endLat = 37.4563, endLon = 126.7052, startAddress = "서울", endAddress = "인천",
            source = "OBD",
        ),
    )
    for (chunk in 0L until 4L) {
        val size = if (chunk == 3L) 200 else 600
        db.tripSeriesQueries.upsert(
            trip_id = TRIP, signal_id = "VEHICLE_SPEED", chunk_index = chunk,
            t0_s = chunk * 600, n = size.toLong(),
            // A NaN every 100th second: a signal the car did not answer for. It has to come
            // back as a hole, not as a plausible zero.
            series = FloatArray(size) { if (it % 100 == 0) Float.NaN else (chunk * 600 + it) % 101f },
            min_v = 0.0, max_v = 100.0, avg_v = 50.5,
        )
    }
    for (chunk in 0L until 2L) {
        db.tripGpsQueries.upsert(
            trip_id = TRIP, chunk_index = chunk, t0_s = chunk * 600, n = 600,
            lat = DoubleArray(600) { 37.5665 + it * 1e-5 },
            lon = DoubleArray(600) { 126.9780 + it * 1e-5 },
            alt = FloatArray(600) { 38f + it },
            speed = FloatArray(600) { (it % 90).toFloat() },
            bearing = FloatArray(600) { (it % 360).toFloat() },
        )
    }
    db.tripEventQueries.insert(
        id = "evt-1", trip_id = TRIP, ts_ms = 60_000, type = "HARSH_BRAKE",
        severity = 4.2, lat = 37.5, lon = 127.0,
    )
    db.dtcEventQueries.insertOrIgnore(
        id = "dtc-1", vehicle_id = CAR, code = "P0128", status = "stored", ecu = "7E8",
        first_seen_ms = 10, last_seen_ms = 20, cleared_ms = null, freeze_frame = null,
    )
    db.dashboardLayoutQueries.insertOrIgnore(
        id = "lay-1", vehicle_id = CAR, name = "내 대시보드", is_active = 1, layout_json = """{"tiles":[]}""",
    )
    settings.setUnits(UnitPreferences.METRIC.with(Quantity.SPEED, UnitId.MPH))
    settings.setThemeMode(ThemeMode.DARK)
    settings.setAcquisitionSource(AcquisitionSource.MONITORING)
    settings.setAutoDriveDetectSpeedKmh(30)
    settings.setActiveVehicleId(CAR)
    bookmarks.toggle(MetricKey.Metric(SuggestedMetric.SPEED))
    bookmarks.toggle(MetricKey.Signal("ENGINE_RPM"))
}

class BackupServiceTest {

    private val from = Device()

    private suspend fun exported(scope: CoroutineScope, password: String = PASSWORD): ByteArray {
        val sink = ByteBufferSink()
        assertEquals(BackupOutcome.OK, from.service(scope).export(sink, password))
        return sink.bytes()
    }

    // --- The round trip --------------------------------------------------------

    @Test
    fun `everything backed up comes back on an empty device`() = runTest {
        from.seed(backgroundScope)
        val bytes = exported(backgroundScope)

        val onto = Device()
        assertEquals(BackupOutcome.OK, onto.service(backgroundScope).import(sourceOf(bytes), PASSWORD))

        assertEquals(from.counts(), onto.counts())

        val vehicle = onto.db.vehicleQueries.selectById(CAR).executeAsOne()
        assertEquals(VIN, vehicle.vin)
        assertEquals("내 EV6", vehicle.display_name)
        assertEquals(2023L, vehicle.model_year)

        val trip = onto.db.tripQueries.selectById(TRIP).executeAsOne()
        assertEquals(31_400.0, trip.distance_m, 1e-9)
        assertEquals(104.0, trip.max_speed_kmh, 1e-9)
        assertEquals(2_400_000L, trip.ended_ms)
        assertEquals("서울", trip.start_address)
        assertEquals("인천", trip.end_address)
        assertEquals(37.5665, trip.start_lat!!, 1e-9)
        assertEquals("OBD", trip.source)

        val layout = onto.db.dashboardLayoutQueries.selectById("lay-1").executeAsOne()
        assertEquals("내 대시보드", layout.name)
        assertEquals("""{"tiles":[]}""", layout.layout_json)

        val dtc = onto.db.dtcEventQueries.selectAllForVehicle(CAR).executeAsOne()
        assertEquals("P0128", dtc.code)
        assertEquals("7E8", dtc.ecu)

        val event = onto.db.tripEventQueries.selectForTrip(TRIP).executeAsOne()
        assertEquals("HARSH_BRAKE", event.type)
        assertEquals(4.2, event.severity, 1e-9)
    }

    /**
     * Bit-exact, NaN included. A restored second that reads 0.0 where the car answered nothing
     * would be a fabricated reading about a drive that already happened, and nothing downstream
     * could tell.
     */
    @Test
    fun `every recorded second survives the round trip, holes included`() = runTest {
        from.seed(backgroundScope)
        val bytes = exported(backgroundScope)
        val onto = Device()
        onto.service(backgroundScope).import(sourceOf(bytes), PASSWORD)

        val original = DefaultTripRepository(from.db, backgroundScope).series(TRIP, "VEHICLE_SPEED")!!
        val restored = DefaultTripRepository(onto.db, backgroundScope).series(TRIP, "VEHICLE_SPEED")!!

        assertEquals(2_000, restored.values.size)
        assertEquals(original.values.size, restored.values.size)
        for (i in original.values.indices) {
            assertEquals(
                original.values[i].toRawBits(),
                restored.values[i].toRawBits(),
                "second $i",
            )
        }
        assertTrue(restored.values[0].isNaN(), "the hole at second 0 came back as a number")
    }

    @Test
    fun `the route survives with its full double precision`() = runTest {
        from.seed(backgroundScope)
        val bytes = exported(backgroundScope)
        val onto = Device()
        onto.service(backgroundScope).import(sourceOf(bytes), PASSWORD)

        val original = DefaultTripRepository(from.db, backgroundScope).track(TRIP)
        val restored = DefaultTripRepository(onto.db, backgroundScope).track(TRIP)
        assertEquals(1_200, restored.size)
        assertEquals(original, restored)

        val chunk = onto.db.tripGpsQueries.selectChunk(TRIP, 0L).executeAsOne()
        assertEquals(38f, chunk.alt[0])
        assertEquals(359f, chunk.bearing[359])
    }

    @Test
    fun `preferences and starred signals come back`() = runTest {
        from.seed(backgroundScope)
        val bytes = exported(backgroundScope)
        val onto = Device()
        onto.service(backgroundScope).import(sourceOf(bytes), PASSWORD)

        val settings = onto.settings.settings.first()
        assertEquals(UnitId.MPH, settings.units[Quantity.SPEED])
        assertEquals(ThemeMode.DARK, settings.themeMode)
        assertEquals(AcquisitionSource.MONITORING, settings.acquisitionSource)
        assertEquals(30, settings.autoDriveDetectSpeedKmh)
        assertEquals(CAR, settings.activeVehicleId)
        assertEquals(
            setOf(MetricKey.Metric(SuggestedMetric.SPEED), MetricKey.Signal("ENGINE_RPM")),
            onto.bookmarks.bookmarks.first(),
        )
    }

    // --- Importing twice -------------------------------------------------------

    /**
     * The requirement UUID ids exist for. Restore the same file twice — because the user was
     * not sure it worked the first time — and there must be one of everything, not two.
     */
    @Test
    fun `importing the same file twice duplicates nothing`() = runTest {
        from.seed(backgroundScope)
        val bytes = exported(backgroundScope)

        val onto = Device()
        onto.service(backgroundScope).import(sourceOf(bytes), PASSWORD)
        val afterFirst = onto.counts()
        assertEquals(BackupOutcome.OK, onto.service(backgroundScope).import(sourceOf(bytes), PASSWORD))

        assertEquals(afterFirst, onto.counts())
        assertEquals(from.counts(), onto.counts())
        assertEquals(
            setOf(MetricKey.Metric(SuggestedMetric.SPEED), MetricKey.Signal("ENGINE_RPM")),
            onto.bookmarks.bookmarks.first(),
        )
    }

    // --- Merging ---------------------------------------------------------------

    /**
     * The phone being restored onto is not empty. A user who has been driving for a month and
     * then restores an old backup must end up with both months, not with the older one.
     */
    @Test
    fun `restoring onto a device that already has drives keeps both`() = runTest {
        from.seed(backgroundScope)
        val bytes = exported(backgroundScope)

        val onto = Device()
        DefaultTripRepository(onto.db, backgroundScope).import(
            TripSummary(
                id = OTHER_TRIP, vehicleId = null, startedMs = 9_000_000, endedMs = 9_600_000,
                distanceM = 4_000.0, fuelMl = 0.0, energyWh = 0.0, maxSpeedKmh = 60.0,
                idleMs = 0, sampleCount = 600, source = "GPS",
            ),
        )
        onto.service(backgroundScope).import(sourceOf(bytes), PASSWORD)

        assertEquals(2L, onto.db.tripQueries.countAll().executeAsOne())
        assertNotNull(onto.db.tripQueries.selectById(OTHER_TRIP).executeAsOneOrNull())
        assertNotNull(onto.db.tripQueries.selectById(TRIP).executeAsOneOrNull())
    }

    /**
     * Same car, paired on both phones under different UUIDs. The VIN is uniquely indexed, so
     * without remapping the backup's vehicle id onto the local one, every trip in the file would
     * point at a vehicle row that was silently not inserted — and fail the foreign key.
     */
    @Test
    fun `a car already paired on this phone is matched by VIN, not duplicated`() = runTest {
        from.seed(backgroundScope)
        val bytes = exported(backgroundScope)

        val onto = Device()
        val localId = "dddddddd-4444-4444-8444-dddddddddddd"
        onto.db.vehicleQueries.insertOrIgnore(
            id = localId, vin = VIN, make = "Kia", model = "EV6", model_year = 2023,
            obdb_repo = null, display_name = "여기서 부른 이름", protocol_num = null,
            last_connected_ms = null, created_ms = 5,
        )

        assertEquals(BackupOutcome.OK, onto.service(backgroundScope).import(sourceOf(bytes), PASSWORD))

        assertEquals(1L, onto.db.vehicleQueries.countAll().executeAsOne())
        // The name the user gave the car on *this* phone wins — a restore does not overwrite.
        assertEquals("여기서 부른 이름", onto.db.vehicleQueries.selectById(localId).executeAsOne().display_name)
        assertEquals(localId, onto.db.tripQueries.selectById(TRIP).executeAsOne().vehicle_id)
        assertEquals(localId, onto.db.dashboardLayoutQueries.selectById("lay-1").executeAsOne().vehicle_id)
        assertEquals(localId, onto.settings.settings.first().activeVehicleId)
    }

    // --- Failure -------------------------------------------------------------

    @Test
    fun `the wrong password is refused and writes nothing`() = runTest {
        from.seed(backgroundScope)
        val bytes = exported(backgroundScope)

        val onto = Device()
        assertEquals(
            BackupOutcome.WRONG_PASSWORD,
            onto.service(backgroundScope).import(sourceOf(bytes), "틀린암호입니다"),
        )
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L), onto.counts())
        assertNull(onto.settings.settings.first().activeVehicleId)
    }

    @Test
    fun `an empty password is refused rather than used as a key`() = runTest {
        from.seed(backgroundScope)
        val onto = Device()
        assertEquals(BackupOutcome.FAILED, from.service(backgroundScope).export(ByteBufferSink(), ""))
        assertEquals(
            BackupOutcome.WRONG_PASSWORD,
            onto.service(backgroundScope).import(sourceOf(exported(backgroundScope)), ""),
        )
    }

    @Test
    fun `a file that is not a backup is refused and writes nothing`() = runTest {
        val onto = Device()
        assertEquals(
            BackupOutcome.FAILED,
            onto.service(backgroundScope).import(sourceOf("사진입니다".encodeToByteArray()), PASSWORD),
        )
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L), onto.counts())
    }

    @Test
    fun `a backup written by a newer app is refused`() = runTest {
        from.seed(backgroundScope)
        val bytes = exported(backgroundScope)
        val end = bytes.indexOf('\n'.code.toByte())
        val bumped = bytes.copyOfRange(0, end).decodeToString()
            .replaceFirst("\"version\":$BACKUP_VERSION", "\"version\":${BACKUP_VERSION + 1}")
            .encodeToByteArray() + byteArrayOf('\n'.code.toByte()) + bytes.copyOfRange(end + 1, bytes.size)

        val onto = Device()
        assertEquals(
            BackupOutcome.UNSUPPORTED_VERSION,
            onto.service(backgroundScope).import(sourceOf(bumped), PASSWORD),
        )
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L), onto.counts())
    }

    @Test
    fun `an empty device exports a readable file with nothing in it`() = runTest {
        val bytes = exported(backgroundScope)
        val onto = Device()
        assertEquals(BackupOutcome.OK, onto.service(backgroundScope).import(sourceOf(bytes), PASSWORD))
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L, 0L, 0L), onto.counts())
    }
}
