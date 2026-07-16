package com.bruni.carscan.obd

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bruni.carscan.core.data.DefaultTripRepository
import com.bruni.carscan.core.data.GpsFix
import com.bruni.carscan.core.data.LocationSource
import com.bruni.carscan.core.data.ReverseGeocoder
import com.bruni.carscan.core.database.createDatabase
import com.bruni.carscan.db.CarScanDb
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNull

private const val VEHICLE = "veh-1"

/**
 * GpsRecorder is driven entirely by `TripRepository.activeTrip`: it does not decide when
 * a trip starts or ends, only reacts. So the test drives trips through the real
 * repository — `trips.start` / `trips.stop` — rather than poking activeTrip directly.
 */
class GpsRecorderTest {

    private fun seedVehicle(db: CarScanDb) {
        db.vehicleQueries.insertOrIgnore(
            id = VEHICLE, vin = null, make = "Kia", model = "EV6", model_year = 2023,
            obdb_repo = "Kia-EV6", display_name = null, protocol_num = 6,
            last_connected_ms = null, created_ms = 0,
        )
    }

    @Test
    fun `fixes are written to trip_gps and the start address is set from the first fix`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CarScanDb.Schema.create(driver)
        val db = createDatabase(driver)
        seedVehicle(db)

        val trips = DefaultTripRepository(db, backgroundScope, flushIntervalMs = 1_000L)
        val location = FakeLocationSource()
        val geocoder = FakeReverseGeocoder("Seoul City Hall")

        GpsRecorder(db, trips, location, geocoder).start(backgroundScope)

        val id = trips.start(VEHICLE, startedMs = 0)
        runCurrent() // let the recorder's fix-collector job actually attach before emitting
        location.emit(GpsFix(tsMs = 0, lat = 37.5665, lon = 126.9780, altM = 10f, speedKmh = 0f, bearingDeg = 0f))
        runCurrent()
        location.emit(GpsFix(tsMs = 1_000, lat = 37.5670, lon = 126.9785, altM = 11f, speedKmh = 20f, bearingDeg = 45f))
        runCurrent()
        trips.stop(endedMs = 2_000)
        runCurrent()

        val trip = db.tripQueries.selectById(id).executeAsOne()
        trip.start_lat shouldBe 37.5665
        trip.start_lon shouldBe 126.9780
        trip.start_address shouldBe "Seoul City Hall"

        val chunk = db.tripGpsQueries.selectTrip(id).executeAsOne()
        chunk.lat[0] shouldBe 37.5665
        chunk.lat[1] shouldBe 37.5670
    }

    @Test
    fun `stopping the trip sets the end address from the last fix seen`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CarScanDb.Schema.create(driver)
        val db = createDatabase(driver)
        seedVehicle(db)

        val trips = DefaultTripRepository(db, backgroundScope, flushIntervalMs = 1_000L)
        val location = FakeLocationSource()
        val geocoder = FakeReverseGeocoder("Gangnam Station")

        GpsRecorder(db, trips, location, geocoder).start(backgroundScope)

        val id = trips.start(VEHICLE, startedMs = 0)
        runCurrent() // let the recorder's fix-collector job actually attach before emitting
        location.emit(GpsFix(tsMs = 0, lat = 37.0, lon = 127.0, altM = 0f, speedKmh = 0f, bearingDeg = 0f))
        runCurrent()
        location.emit(GpsFix(tsMs = 1_000, lat = 37.4979, lon = 127.0276, altM = 5f, speedKmh = 30f, bearingDeg = 90f))
        runCurrent()
        trips.stop(endedMs = 2_000)
        runCurrent()

        val trip = db.tripQueries.selectById(id).executeAsOne()
        trip.end_lat shouldBe 37.4979
        trip.end_lon shouldBe 127.0276
        trip.end_address shouldBe "Gangnam Station"
    }

    @Test
    fun `a trip with zero fixes leaves every GPS column null`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CarScanDb.Schema.create(driver)
        val db = createDatabase(driver)
        seedVehicle(db)

        val trips = DefaultTripRepository(db, backgroundScope, flushIntervalMs = 1_000L)
        val location = FakeLocationSource()
        val geocoder = FakeReverseGeocoder("unused")

        GpsRecorder(db, trips, location, geocoder).start(backgroundScope)

        val id = trips.start(VEHICLE, startedMs = 0)
        runCurrent()
        trips.stop(endedMs = 1_000)
        runCurrent()

        val trip = db.tripQueries.selectById(id).executeAsOne()
        assertNull(trip.start_lat)
        assertNull(trip.start_lon)
        assertNull(trip.start_address)
        assertNull(trip.end_lat)
        assertNull(trip.end_lon)
        assertNull(trip.end_address)
        db.tripGpsQueries.countAll().executeAsOne() shouldBe 0L
    }
}

private class FakeLocationSource : LocationSource {
    private val _fixes = MutableSharedFlow<GpsFix>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    override val fixes: Flow<GpsFix> = _fixes.asSharedFlow()

    suspend fun emit(fix: GpsFix) = _fixes.emit(fix)
}

private class FakeReverseGeocoder(private val canned: String?) : ReverseGeocoder {
    override suspend fun address(lat: Double, lon: Double): String? = canned
}
