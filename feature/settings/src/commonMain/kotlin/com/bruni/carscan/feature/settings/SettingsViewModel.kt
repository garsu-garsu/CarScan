package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.data.backup.BackupOutcome
import com.bruni.carscan.core.data.backup.BackupService
import com.bruni.carscan.core.data.backup.BackupSink
import com.bruni.carscan.core.data.backup.BackupSource
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.UnitId
import kotlinx.coroutines.launch

/**
 * The settings screen. Every intent here is a straight pass-through to [SettingsRepository] —
 * [state] just mirrors what comes back out of it, so two screens open at once agree.
 *
 * Backup is the exception: [exportTo] and [importFrom] are called directly by the screen rather
 * than through an intent, because they have to run while the platform still holds the picked file
 * open. See `rememberBackupLauncher`.
 */
class SettingsViewModel(
    private val settings: SettingsRepository,
    private val backup: BackupService,
) : MviViewModel<SettingsState, SettingsIntent, SettingsEffect>(SettingsState()) {

    /**
     * Deliberately not in [SettingsState]: it lives only from the prompt to the moment the file
     * is written or read, and UI state is the wrong place for a password.
     */
    private var password: String = ""

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
                    autoDriveDetectSpeedKmh = current.autoDriveDetectSpeedKmh,
                    backgroundTracking = current.backgroundTracking,
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
        is SettingsIntent.SetAutoDriveDetectSpeedKmh -> setAutoDriveDetectSpeedKmh(intent.kmh)
        is SettingsIntent.SetBackgroundTracking -> setBackgroundTracking(intent.enabled)
        is SettingsIntent.StartBackup -> setState { copy(backup = BackupStep.Password(intent.mode)) }
        is SettingsIntent.ConfirmBackupPassword -> confirmPassword(intent.password)
        SettingsIntent.DismissBackup -> dismissBackup()
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

    private fun setAutoDriveDetectSpeedKmh(kmh: Int) {
        scope.launch { settings.setAutoDriveDetectSpeedKmh(kmh) }
    }

    private fun setBackgroundTracking(enabled: Boolean) {
        scope.launch { settings.setBackgroundTracking(enabled) }
    }

    private fun confirmPassword(entered: String) {
        val mode = (state.value.backup as? BackupStep.Password)?.mode ?: return
        password = entered
        setState { copy(backup = BackupStep.Picking(mode)) }
    }

    /** Also the way out of [BackupStep.Picking] — a cancelled file chooser reports nothing else. */
    private fun dismissBackup() {
        password = ""
        setState { copy(backup = null) }
    }

    /**
     * Runs while the platform holds the chosen file open — see the class KDoc. A file chooser
     * that came back after the user dismissed the flow has nothing to write, so it is dropped.
     */
    suspend fun exportTo(sink: BackupSink) {
        val mode = (state.value.backup as? BackupStep.Picking)?.mode ?: return
        setState { copy(backup = BackupStep.Working(mode)) }
        finish(mode, backup.export(sink, password))
    }

    suspend fun importFrom(source: BackupSource) {
        val mode = (state.value.backup as? BackupStep.Picking)?.mode ?: return
        setState { copy(backup = BackupStep.Working(mode)) }
        finish(mode, backup.import(source, password))
    }

    private fun finish(mode: BackupMode, outcome: BackupOutcome) {
        password = ""
        setState { copy(backup = BackupStep.Done(mode, outcome)) }
    }
}
