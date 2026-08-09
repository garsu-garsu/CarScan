package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.data.backup.BackupOutcome
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
    /** The GPS speed DrivingDetector treats as "driving" — see `Settings`. */
    val autoDriveDetectSpeedKmh: Int = 20,
    /** The opt-in for background trip tracking — see `Settings`. */
    val backgroundTracking: Boolean = false,
    /** Null when the user is not in the middle of backing up or restoring. */
    val backup: BackupStep? = null,
)

/** Which direction the user chose. The two flows differ only in wording and in the file chooser. */
enum class BackupMode { EXPORT, IMPORT }

/**
 * Where a backup or restore has got to.
 *
 * The password itself is **not** in here. UI state is held, logged and inspected; a password the
 * user typed has no business living in it. `SettingsViewModel` keeps it in a private field for
 * the few seconds between the prompt and the file chooser.
 */
sealed interface BackupStep {
    /** Asking for the password. */
    data class Password(val mode: BackupMode) : BackupStep

    /** The platform file chooser should be open. Cancelling it is the way out. */
    data class Picking(val mode: BackupMode) : BackupStep

    /** Encrypting or restoring. Can take a while on a long history, so the UI says so. */
    data class Working(val mode: BackupMode) : BackupStep

    data class Done(val mode: BackupMode, val outcome: BackupOutcome) : BackupStep
}

sealed interface SettingsIntent {
    /** Changes the display unit for one quantity, leaving the other eight alone. */
    data class SetUnit(val quantity: Quantity, val unit: UnitId) : SettingsIntent
    data class SetGaugeStyle(val style: String) : SettingsIntent
    data class SetThemeMode(val mode: ThemeMode) : SettingsIntent
    data class SetKeepScreenOn(val enabled: Boolean) : SettingsIntent
    data class SetRecordTrips(val enabled: Boolean) : SettingsIntent
    data class SetAutoReconnect(val enabled: Boolean) : SettingsIntent
    data class SetAcquisitionSource(val source: AcquisitionSource) : SettingsIntent
    data class SetAutoDriveDetectSpeedKmh(val kmh: Int) : SettingsIntent
    data class SetBackgroundTracking(val enabled: Boolean) : SettingsIntent

    /** The 백업/복원 rows — opens the password prompt. */
    data class StartBackup(val mode: BackupMode) : SettingsIntent

    /** The password prompt was submitted; the file chooser opens next. */
    data class ConfirmBackupPassword(val password: String) : SettingsIntent

    /** Dismissed the prompt, backed out of the file chooser, or closed the result. */
    data object DismissBackup : SettingsIntent

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
