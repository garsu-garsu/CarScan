package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.data.AcquisitionSource
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
    val autoReconnect: Boolean = true,
    /** Which screen's signals the poller falls back to off the dashboard — see `Settings`. */
    val acquisitionSource: AcquisitionSource = AcquisitionSource.DASHBOARD,
)

sealed interface SettingsIntent {
    /** Changes the display unit for one quantity, leaving the other eight alone. */
    data class SetUnit(val quantity: Quantity, val unit: UnitId) : SettingsIntent
    data class SetGaugeStyle(val style: String) : SettingsIntent
    data class SetThemeMode(val mode: ThemeMode) : SettingsIntent
    data class SetKeepScreenOn(val enabled: Boolean) : SettingsIntent
    data class SetRecordTrips(val enabled: Boolean) : SettingsIntent
    data class SetAutoReconnect(val enabled: Boolean) : SettingsIntent
    data class SetAcquisitionSource(val source: AcquisitionSource) : SettingsIntent

    /** The About & Licenses row. Features never navigate themselves — see [SettingsEffect]. */
    data object OpenAbout : SettingsIntent

    /** The Vehicle row — opens the garage / vehicle picker. */
    data object OpenVehicle : SettingsIntent

    /** The Premium row — opens the paywall. */
    data object OpenPremium : SettingsIntent
}

sealed interface SettingsEffect {
    data object OpenAbout : SettingsEffect
    data object OpenGarage : SettingsEffect
    data object OpenPaywall : SettingsEffect
}
