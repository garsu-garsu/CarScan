package com.bruni.carscan.feature.dashboard

import com.bruni.carscan.core.data.ActiveVehicle
import com.bruni.carscan.core.data.DashboardLayout
import com.bruni.carscan.core.data.DashboardLayoutRepository
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.data.VehicleSessionRepository
import com.bruni.carscan.core.data.VisibleSignals
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.SpeedUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSession : VehicleSessionRepository {
    val latestSamples = MutableStateFlow<Map<MetricKey, SensorSample>>(emptyMap())
    override val latest: StateFlow<Map<MetricKey, SensorSample>> = latestSamples
    override val samples: SharedFlow<SensorSample> = MutableSharedFlow()
    override val health: StateFlow<SessionHealth> = MutableStateFlow(SessionHealth())

    fun emit(sample: SensorSample) {
        latestSamples.value = latestSamples.value + (sample.key to sample)
    }
}

/** An in-memory `DashboardLayoutRepository`. Survives the ViewModel that wrote to it, which is the point. */
class FakeLayouts : DashboardLayoutRepository {
    val saved = mutableMapOf<String, DashboardLayout>()

    override suspend fun save(layout: DashboardLayout) {
        saved[layout.id] = layout
    }

    override suspend fun forVehicle(vehicleId: String): List<DashboardLayout> =
        saved.values.filter { it.vehicleId == vehicleId || it.vehicleId == null }

    override suspend fun activeFor(vehicleId: String): DashboardLayout? =
        saved.values.firstOrNull { it.isActive && (it.vehicleId == vehicleId || it.vehicleId == null) }

    override suspend fun activate(id: String, vehicleId: String?) {
        saved.replaceAll { _, l -> l.copy(isActive = l.id == id) }
    }

    override suspend fun delete(id: String) {
        saved.remove(id)
    }
}

@Suppress("DEPRECATION")
class FakeSettings(initial: Settings = Settings(activeVehicleId = VEHICLE_ID)) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state

    override suspend fun setRecordTrips(enabled: Boolean) {
        state.value = state.value.copy(recordTrips = enabled)
    }

    override suspend fun setUnit(quantity: Quantity, unit: UnitId) {
        setUnits(state.value.units.with(quantity, unit))
    }

    override suspend fun setUnits(units: UnitPreferences) {
        state.value = state.value.copy(units = units)
    }

    override suspend fun setSpeedUnit(unit: SpeedUnit) {
        setUnit(
            Quantity.SPEED,
            if (unit == SpeedUnit.MILES_PER_HOUR) UnitId.MPH else UnitId.KMH,
        )
    }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        state.value = state.value.copy(keepScreenOn = enabled)
    }

    override suspend fun setActiveVehicleId(id: String?) {
        state.value = state.value.copy(activeVehicleId = id)
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        state.value = state.value.copy(themeMode = mode)
    }

    override suspend fun setGaugeStyle(style: String) {
        state.value = state.value.copy(gaugeStyle = style)
    }

    override suspend fun setAutoReconnect(enabled: Boolean) {
        state.value = state.value.copy(autoReconnect = enabled)
    }
}

class FakeActiveVehicle(
    signalset: EffectiveSignalset? = null,
    unsupported: Set<String> = emptySet(),
) : ActiveVehicle {
    override val signalset = MutableStateFlow(signalset)
    override val unsupported = MutableStateFlow(unsupported)
}

/**
 * Records what the poller was told is on screen.
 *
 * [visible] starts at null — *not* at the empty set — so a test can tell "the dashboard never
 * called setVisible" apart from "the dashboard said nothing is on screen". If the ViewModel's
 * call is deleted, this stays null and the visibility tests fail, which is exactly what they are
 * for.
 */
class FakeVisibleSignals : VisibleSignals {
    val visible = MutableStateFlow<Set<MetricKey>?>(null)
    val calls = mutableListOf<Set<MetricKey>>()

    override fun setVisible(keys: Set<MetricKey>) {
        calls += keys
        visible.value = keys
    }
}

/**
 * A wall clock the test moves by hand — no real waiting, and no monotonic/epoch confusion.
 *
 * [now] is in the same base as `SensorSample.timestampMs`, which is what makes the staleness
 * comparison meaningful. Starting well past zero so that a sample stamped with a plausible epoch
 * cannot accidentally look fresh against a clock that begins at 0.
 */
class FakeClock(var now: Long = 1_700_000_000_000) : DashboardClock {
    override fun epochMs(): Long = now
}

const val VEHICLE_ID = "vehicle-1"
