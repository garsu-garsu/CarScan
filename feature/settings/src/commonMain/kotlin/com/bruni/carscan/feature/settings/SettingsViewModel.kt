package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.UnitId
import kotlinx.coroutines.launch

/**
 * The settings screen. Every intent here is a straight pass-through to [SettingsRepository] —
 * [state] just mirrors what comes back out of it, so two screens open at once agree.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
) : MviViewModel<SettingsState, SettingsIntent, SettingsEffect>(SettingsState()) {

    init {
        settings.settings.collectIntoState { current ->
            setState {
                copy(
                    units = current.units,
                    gaugeStyle = current.gaugeStyle,
                    themeMode = current.themeMode,
                    keepScreenOn = current.keepScreenOn,
                    recordTrips = current.recordTrips,
                    autoReconnect = current.autoReconnect,
                    acquisitionSource = current.acquisitionSource,
                )
            }
        }
    }

    override fun onIntent(intent: SettingsIntent) = when (intent) {
        is SettingsIntent.SetUnit -> setUnit(intent.quantity, intent.unit)
        is SettingsIntent.SetGaugeStyle -> setGaugeStyle(intent.style)
        is SettingsIntent.SetThemeMode -> setThemeMode(intent.mode)
        is SettingsIntent.SetKeepScreenOn -> setKeepScreenOn(intent.enabled)
        is SettingsIntent.SetRecordTrips -> setRecordTrips(intent.enabled)
        is SettingsIntent.SetAutoReconnect -> setAutoReconnect(intent.enabled)
        is SettingsIntent.SetAcquisitionSource -> setAcquisitionSource(intent.source)
        SettingsIntent.OpenAbout -> emitEffect(SettingsEffect.OpenAbout)
        SettingsIntent.OpenVehicle -> emitEffect(SettingsEffect.OpenGarage)
        SettingsIntent.OpenPremium -> emitEffect(SettingsEffect.OpenPaywall)
    }

    private fun setUnit(quantity: Quantity, unit: UnitId) {
        scope.launch { settings.setUnit(quantity, unit) }
    }

    private fun setGaugeStyle(style: String) {
        scope.launch { settings.setGaugeStyle(style) }
    }

    private fun setThemeMode(mode: ThemeMode) {
        scope.launch { settings.setThemeMode(mode) }
    }

    private fun setKeepScreenOn(enabled: Boolean) {
        scope.launch { settings.setKeepScreenOn(enabled) }
    }

    private fun setRecordTrips(enabled: Boolean) {
        scope.launch { settings.setRecordTrips(enabled) }
    }

    private fun setAutoReconnect(enabled: Boolean) {
        scope.launch { settings.setAutoReconnect(enabled) }
    }

    private fun setAcquisitionSource(source: AcquisitionSource) {
        scope.launch { settings.setAcquisitionSource(source) }
    }
}
