package com.bruni.carscan.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bruni.carscan.core.units.SpeedUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User preferences.
 *
 * [speedUnit] is a *display* preference and nothing else. Samples are recorded in the
 * unit OBDb declared them in and converted once, when they are drawn — so changing
 * this cannot retroactively corrupt a single stored trip.
 *
 * [recordTrips] defaults to **off**. That is a storage decision, not a UI one: with it
 * on, every minute the app spends open in someone's car writes to disk, and the gauges
 * need none of it — they read the live sample stream.
 */
data class Settings(
    val recordTrips: Boolean = false,
    val speedUnit: SpeedUnit = SpeedUnit.KM_PER_HOUR,
    val keepScreenOn: Boolean = true,
    val activeVehicleId: String? = null,
)

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setRecordTrips(enabled: Boolean)
    suspend fun setSpeedUnit(unit: SpeedUnit)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setActiveVehicleId(id: String?)
}

private val RECORD_TRIPS = booleanPreferencesKey("record_trips")
private val SPEED_UNIT = stringPreferencesKey("speed_unit")
private val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
private val ACTIVE_VEHICLE = stringPreferencesKey("active_vehicle_id")

class DefaultSettingsRepository(
    private val store: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<Settings> = store.data.map { prefs ->
        val defaults = Settings()
        Settings(
            recordTrips = prefs[RECORD_TRIPS] ?: defaults.recordTrips,
            // An unrecognised stored value falls back to the default rather than
            // throwing: a preferences file from a newer build must not brick the app.
            speedUnit = prefs[SPEED_UNIT]
                ?.let { name -> SpeedUnit.entries.firstOrNull { it.name == name } }
                ?: defaults.speedUnit,
            keepScreenOn = prefs[KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            activeVehicleId = prefs[ACTIVE_VEHICLE],
        )
    }

    override suspend fun setRecordTrips(enabled: Boolean) {
        store.edit { it[RECORD_TRIPS] = enabled }
    }

    override suspend fun setSpeedUnit(unit: SpeedUnit) {
        store.edit { it[SPEED_UNIT] = unit.name }
    }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        store.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    override suspend fun setActiveVehicleId(id: String?) {
        store.edit { prefs ->
            if (id == null) prefs.remove(ACTIVE_VEHICLE) else prefs[ACTIVE_VEHICLE] = id
        }
    }
}
