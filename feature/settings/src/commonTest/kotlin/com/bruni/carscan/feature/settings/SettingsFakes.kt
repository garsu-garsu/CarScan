package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.SpeedUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state

    override suspend fun setRecordTrips(enabled: Boolean) {
        state.value = state.value.copy(recordTrips = enabled)
    }

    override suspend fun setUnit(quantity: Quantity, unit: UnitId) {
        state.value = state.value.copy(units = state.value.units.with(quantity, unit))
    }

    override suspend fun setUnits(units: UnitPreferences) {
        state.value = state.value.copy(units = units)
    }

    @Deprecated("Use setUnit(Quantity.SPEED, …).", ReplaceWith("setUnit(Quantity.SPEED, unit)"))
    override suspend fun setSpeedUnit(unit: SpeedUnit) {
        setUnit(Quantity.SPEED, if (unit == SpeedUnit.MILES_PER_HOUR) UnitId.MPH else UnitId.KMH)
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
}
