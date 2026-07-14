package com.bruni.carscan.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.sqldelight.db.SqlDriver
import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A driver over a throwaway database, PRAGMAs applied. Mirrors the one in :core:database. */
expect fun createTestDriver(path: String? = null): SqlDriver

const val VEHICLE = "veh-1"

fun CarScanDb.seedVehicle(id: String = VEHICLE) {
    vehicleQueries.insertOrIgnore(
        id = id, vin = null, make = "Kia", model = "EV6", model_year = 2023,
        obdb_repo = "Kia-EV6", display_name = null, protocol_num = 6,
        last_connected_ms = null, created_ms = 0,
    )
}

/**
 * Stands in for `PidScheduler`, which is being built in :core:obd right now. The
 * repository is coded against the [SampleSource] port rather than the scheduler, so
 * neither has to wait for the other — and the ELM327 emulator is a drop-in for the
 * same reason.
 */
class FakeSampleSource : SampleSource {
    private val _samples = MutableSharedFlow<SensorSample>(
        replay = 0,
        extraBufferCapacity = 8192,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val samples: SharedFlow<SensorSample> = _samples.asSharedFlow()

    private val _health = MutableStateFlow(SessionHealth())
    override val health: StateFlow<SessionHealth> = _health.asStateFlow()

    fun emit(sample: SensorSample) {
        _samples.tryEmit(sample)
    }

    fun report(health: SessionHealth) {
        _health.value = health
    }
}

/**
 * DataStore, in memory.
 *
 * The code under test is the Preferences-to-[Settings] mapping — the defaults, the
 * enum fallback, removing a key on null. The file engine underneath belongs to
 * androidx, and exercising it here would only test their atomic rename, which does
 * not survive a Windows JVM host anyway (it is fine on Android and iOS, which are the
 * platforms that ship).
 */
class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(
        transform: suspend (t: Preferences) -> Preferences,
    ): Preferences = transform(state.value).also { state.value = it }
}

fun speed(kmh: Double, ts: Long = 0) = SensorSample(
    signalId = "VEHICLE_SPEED",
    key = MetricKey.Metric(SuggestedMetric.SPEED),
    value = DecodedValue.Numeric(kmh),
    unit = ObdUnit.KILOMETERS_PER_HOUR,
    timestampMs = ts,
)

fun rpm(value: Double, ts: Long = 0) = SensorSample(
    signalId = "ENGINE_RPM",
    key = MetricKey.Signal("ENGINE_RPM"),
    value = DecodedValue.Numeric(value),
    unit = ObdUnit.RPM,
    timestampMs = ts,
)

fun coolant(celsius: Double, ts: Long = 0) = SensorSample(
    signalId = "COOLANT_TEMP",
    key = MetricKey.Metric(SuggestedMetric.ENGINE_COOLANT_TEMPERATURE),
    value = DecodedValue.Numeric(celsius),
    unit = ObdUnit.CELSIUS,
    timestampMs = ts,
)
