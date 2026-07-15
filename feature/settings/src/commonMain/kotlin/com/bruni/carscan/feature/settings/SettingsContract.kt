package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences

data class SettingsState(
    val units: UnitPreferences = UnitPreferences.METRIC,
    /** The stable key `SettingsRepository` persists — see `Settings.gaugeStyle`. */
    val gaugeStyle: String = "MODERN_ARC",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val keepScreenOn: Boolean = true,
    val recordTrips: Boolean = false,
)

sealed interface SettingsIntent {
    /** Changes the display unit for one quantity, leaving the other eight alone. */
    data class SetUnit(val quantity: Quantity, val unit: UnitId) : SettingsIntent
    data class SetGaugeStyle(val style: String) : SettingsIntent
    data class SetThemeMode(val mode: ThemeMode) : SettingsIntent
    data class SetKeepScreenOn(val enabled: Boolean) : SettingsIntent
    data class SetRecordTrips(val enabled: Boolean) : SettingsIntent

    /** The About & Licenses row. Features never navigate themselves — see [SettingsEffect]. */
    data object OpenAbout : SettingsIntent
}

sealed interface SettingsEffect {
    data object OpenAbout : SettingsEffect
}
