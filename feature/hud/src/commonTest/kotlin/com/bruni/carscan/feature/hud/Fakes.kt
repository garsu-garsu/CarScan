package com.bruni.carscan.feature.hud

import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.ActiveVehicle
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

class FakeActiveVehicle(
    signalset: EffectiveSignalset? = null,
    unsupported: Set<String> = emptySet(),
) : ActiveVehicle {
    override val signalset = MutableStateFlow(signalset)
    override val unsupported = MutableStateFlow(unsupported)
}

@Suppress("DEPRECATION")
class FakeSettings(initial: Settings = Settings()) : SettingsRepository {
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

    override suspend fun setAcquisitionSource(source: AcquisitionSource) {
        state.value = state.value.copy(acquisitionSource = source)
    }

    override suspend fun setAutoDriveDetectSpeedKmh(kmh: Int) {
        state.value = state.value.copy(autoDriveDetectSpeedKmh = kmh)
    }

    override suspend fun setBackgroundTracking(enabled: Boolean) {
        state.value = state.value.copy(backgroundTracking = enabled)
    }
}

/**
 * Records what the HUD told the poller is on screen.
 *
 * Starts empty rather than pre-seeded, so a test can tell "the HUD never called setVisible" apart
 * from "the HUD said nothing is visible".
 */
class FakeVisibleSignals : VisibleSignals {
    val calls = mutableListOf<Set<MetricKey>>()

    override fun setVisible(keys: Set<MetricKey>) {
        calls += keys
    }
}
