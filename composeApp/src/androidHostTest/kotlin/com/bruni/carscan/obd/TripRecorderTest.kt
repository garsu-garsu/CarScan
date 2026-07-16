package com.bruni.carscan.obd

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.bruni.carscan.core.data.ConnectionState
import com.bruni.carscan.core.data.DefaultTripRepository
import com.bruni.carscan.core.data.SampleSource
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.database.createDatabase
import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.SpeedUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.db.CarScanDb
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Recording is **off by default**, and off has to mean off.
 *
 * The claim is not "few writes" but *none*: with the preference off, an hour of driving with the
 * dashboard open must not cost a single disk write, because the gauges read the live sample stream
 * and need no history at all. A test that only counted rows would be satisfied by a recorder that
 * opened a transaction per sample and rolled it back — so the driver is instrumented, and it is
 * the **statements** that are counted.
 */
class TripRecorderTest {

    @Test
    fun `recording off costs zero database writes, however many samples arrive`() = runTest {
        val world = World(this, recordTrips = false)

        world.connect()
        runCurrent()
        world.drive(samples = 200)
        runCurrent()

        world.driver.executes shouldBe 0L
        world.db.tripQueries.countAll().executeAsOne() shouldBe 0L
    }

    /** And with it on the samples actually land — or the assertion above would guard nothing. */
    @Test
    fun `recording on writes a trip and its samples`() = runTest {
        val world = World(this, recordTrips = true)

        world.connect()
        runCurrent()
        world.drive(samples = 200)
        runCurrent()
        // The adapter goes away, which is what closes a trip out: drain, flush, finish.
        world.disconnect()
        runCurrent()

        world.driver.executes shouldBeGreaterThan 0L
        world.db.tripQueries.countAll().executeAsOne() shouldBe 1L

        val trip = world.db.tripQueries.selectAll().executeAsOne()
        trip.ended_ms shouldBe world.clockMs
        world.db.tripSeriesQueries.selectSignalIds(trip.id).executeAsList() shouldBe listOf("VSS")
    }

    /**
     * Nothing is connected, so there is nothing to record — preference or no preference. A trip
     * opened on the preference alone would start the moment the app launched and fill the history
     * with empty drives.
     */
    @Test
    fun `recording on but disconnected starts no trip`() = runTest {
        val world = World(this, recordTrips = true)

        world.drive(samples = 50)
        runCurrent()

        world.driver.executes shouldBe 0L
        world.db.tripQueries.countAll().executeAsOne() shouldBe 0L
    }
}

/** The recorder, a real SQLite database, and a counting driver between them. */
private class World(scope: TestScope, recordTrips: Boolean) {

    val driver = CountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
    val db: CarScanDb

    var clockMs: Long = 1_700_000_000_000L
        private set

    private val source = FakeSampleSource()
    private val trips: DefaultTripRepository

    init {
        CarScanDb.Schema.create(driver)
        db = createDatabase(driver)

        // backgroundScope, not the test's own: SampleWriter's flush loop and the recorder's two
        // collectors never finish, and runTest waits for the children of its own scope.
        trips = DefaultTripRepository(db, scope.backgroundScope, flushIntervalMs = 1_000L)

        TripRecorder(
            source = source,
            trips = trips,
            settings = FakeSettings(recordTrips),
            vehicleId = VehicleRows(db) { "vehicle-1" }::current,
            nowMs = { clockMs },
        ).start(scope.backgroundScope)

        // Everything above is setup. The count starts here, so that creating the schema is not
        // mistaken for a write on the live path.
        driver.reset()
    }

    fun connect() {
        source.setHealth(SessionHealth(connection = ConnectionState.CONNECTED))
    }

    fun disconnect() {
        source.setHealth(SessionHealth(connection = ConnectionState.DISCONNECTED))
    }

    suspend fun drive(samples: Int) {
        repeat(samples) { index ->
            clockMs += 100
            source.emit(
                SensorSample(
                    signalId = "VSS",
                    key = MetricKey.Signal("VSS"),
                    value = DecodedValue.Numeric(60.0 + index % 10),
                    unit = ObdUnit.KILOMETERS_PER_HOUR,
                    timestampMs = clockMs,
                ),
            )
        }
    }
}

/**
 * Counts prepared statements that *change* something.
 *
 * `executeQuery` is deliberately not counted: reading is not a write, and the claim under test is
 * about what the live path puts on disk.
 */
private class CountingDriver(delegate: SqlDriver) : SqlDriver by delegate {

    private val inner = delegate

    var executes: Long = 0L
        private set

    fun reset() {
        executes = 0L
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        executes++
        return inner.execute(identifier, sql, parameters, binders)
    }
}

private class FakeSampleSource : SampleSource {

    private val _samples = MutableSharedFlow<SensorSample>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val samples: SharedFlow<SensorSample> = _samples.asSharedFlow()

    private val _health = MutableStateFlow(SessionHealth())
    override val health: StateFlow<SessionHealth> = _health.asStateFlow()

    fun setHealth(health: SessionHealth) {
        _health.value = health
    }

    suspend fun emit(sample: SensorSample) = _samples.emit(sample)
}

private class FakeSettings(recordTrips: Boolean) : SettingsRepository {

    override val settings: Flow<Settings> = MutableStateFlow(Settings(recordTrips = recordTrips))

    override suspend fun setRecordTrips(enabled: Boolean) = Unit
    override suspend fun setUnit(quantity: Quantity, unit: UnitId) = Unit
    override suspend fun setUnits(units: UnitPreferences) = Unit

    @Deprecated("Use setUnit(Quantity.SPEED, …).", ReplaceWith("setUnit(Quantity.SPEED, unit)"))
    override suspend fun setSpeedUnit(unit: SpeedUnit) = Unit
    override suspend fun setKeepScreenOn(enabled: Boolean) = Unit
    override suspend fun setActiveVehicleId(id: String?) = Unit
    override suspend fun setThemeMode(mode: ThemeMode) = Unit
    override suspend fun setGaugeStyle(style: String) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit
}
