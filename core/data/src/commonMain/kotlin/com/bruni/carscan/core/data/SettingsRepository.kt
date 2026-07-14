package com.bruni.carscan.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.SpeedUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User preferences.
 *
 * [units] is a *display* preference and nothing else. Samples are recorded in the
 * unit OBDb declared them in and converted once, when they are drawn — so changing
 * this cannot retroactively corrupt a single stored trip.
 *
 * It is a choice **per quantity**, never a metric/imperial switch: a British driver reads
 * miles, litres and imperial MPG simultaneously, and no boolean can say that.
 *
 * [recordTrips] defaults to **off**. That is a storage decision, not a UI one: with it
 * on, every minute the app spends open in someone's car writes to disk, and the gauges
 * need none of it — they read the live sample stream.
 */
data class Settings(
    val recordTrips: Boolean = false,
    val units: UnitPreferences = UnitPreferences.METRIC,
    val keepScreenOn: Boolean = true,
    val activeVehicleId: String? = null,
) {
    /**
     * The speed preference, *read out of* [units].
     *
     * Deliberately not a constructor property: if it were, `copy(units = …)` would leave a stale
     * speed behind it, and the app would hold two disagreeing answers to "km/h or mph?". There is
     * one stored preference, and this is a view of it.
     */
    @Deprecated("Use units[Quantity.SPEED]; SpeedUnit cannot express the other eight quantities.")
    val speedUnit: SpeedUnit
        get() = if (units[Quantity.SPEED] == UnitId.MPH) {
            SpeedUnit.MILES_PER_HOUR
        } else {
            SpeedUnit.KM_PER_HOUR
        }
}

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setRecordTrips(enabled: Boolean)

    /** Sets the display unit for one quantity, leaving the other eight alone. */
    suspend fun setUnit(quantity: Quantity, unit: UnitId)

    /** Replaces every unit at once — what the first-run locale defaults do. */
    suspend fun setUnits(units: UnitPreferences)

    @Deprecated(
        "Use setUnit(Quantity.SPEED, …).",
        ReplaceWith("setUnit(Quantity.SPEED, unit)"),
    )
    suspend fun setSpeedUnit(unit: SpeedUnit)
    suspend fun setKeepScreenOn(enabled: Boolean)
    suspend fun setActiveVehicleId(id: String?)
}

private val RECORD_TRIPS = booleanPreferencesKey("record_trips")
private val UNIT_PREFS = stringPreferencesKey("unit_prefs")
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
            // UnitPreferences.decode does that per quantity, so one junk entry cannot
            // take the other eight down with it.
            units = UnitPreferences.decode(prefs[UNIT_PREFS]),
            keepScreenOn = prefs[KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            activeVehicleId = prefs[ACTIVE_VEHICLE],
        )
    }

    override suspend fun setRecordTrips(enabled: Boolean) {
        store.edit { it[RECORD_TRIPS] = enabled }
    }

    override suspend fun setUnit(quantity: Quantity, unit: UnitId) {
        // Read-modify-write inside the edit, so two screens changing two different
        // quantities cannot clobber each other's choice.
        store.edit { prefs ->
            prefs[UNIT_PREFS] = UnitPreferences.decode(prefs[UNIT_PREFS]).with(quantity, unit).encode()
        }
    }

    override suspend fun setUnits(units: UnitPreferences) {
        store.edit { it[UNIT_PREFS] = units.encode() }
    }

    @Deprecated(
        "Use setUnit(Quantity.SPEED, …).",
        ReplaceWith("setUnit(Quantity.SPEED, unit)"),
    )
    override suspend fun setSpeedUnit(unit: SpeedUnit) {
        setUnit(
            Quantity.SPEED,
            if (unit == SpeedUnit.MILES_PER_HOUR) UnitId.MPH else UnitId.KMH,
        )
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
