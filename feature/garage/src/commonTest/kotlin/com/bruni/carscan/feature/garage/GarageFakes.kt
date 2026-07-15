package com.bruni.carscan.feature.garage

import com.bruni.carscan.core.data.CatalogEntry
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.data.Vehicle
import com.bruni.carscan.core.data.VehicleCatalog
import com.bruni.carscan.core.data.VehicleRepository
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.SpeedUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeVehicleCatalog(private val entries: List<CatalogEntry>) : VehicleCatalog {
    override fun all(): List<CatalogEntry> = entries
}

class FakeVehicleRepository : VehicleRepository {
    /** Every vehicle passed to [remember], in call order — including duplicates. */
    val remembered = mutableListOf<Vehicle>()
    private val vehicles = mutableListOf<Vehicle>()

    override suspend fun all(): List<Vehicle> = vehicles.toList()

    override suspend fun byId(id: String): Vehicle? = vehicles.firstOrNull { it.id == id }

    override suspend fun byVin(vin: String): Vehicle? = vehicles.firstOrNull { it.vin == vin }

    override suspend fun remember(vehicle: Vehicle) {
        remembered += vehicle
        if (vehicles.none { it.id == vehicle.id }) vehicles += vehicle
    }

    override suspend fun touchLastConnected(id: String, atMs: Long) {}

    override suspend fun forget(id: String) {
        vehicles.removeAll { it.id == id }
    }
}

class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state

    /** Every id passed to [setActiveVehicleId], in call order. */
    val activeVehicleIds = mutableListOf<String?>()

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
        activeVehicleIds += id
        state.value = state.value.copy(activeVehicleId = id)
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        state.value = state.value.copy(themeMode = mode)
    }

    override suspend fun setGaugeStyle(style: String) {
        state.value = state.value.copy(gaugeStyle = style)
    }
}
