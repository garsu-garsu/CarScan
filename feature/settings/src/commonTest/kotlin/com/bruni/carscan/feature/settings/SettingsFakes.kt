package com.bruni.carscan.feature.settings

import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.monetization.BillingPort
import com.bruni.carscan.core.monetization.Entitlements
import com.bruni.carscan.core.monetization.Purchase
import com.bruni.carscan.core.monetization.PurchaseKind
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.SpeedUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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

    override suspend fun setAutoReconnect(enabled: Boolean) {
        state.value = state.value.copy(autoReconnect = enabled)
    }

    override suspend fun setAcquisitionSource(source: AcquisitionSource) {
        state.value = state.value.copy(acquisitionSource = source)
    }
}

/** Scriptable billing fake: hands back a fixed price map, or throws to simulate an offline store. */
class FakeBillingPort(
    private val prices: Map<PurchaseKind, String> = emptyMap(),
    private val failPrices: Boolean = false,
) : BillingPort {
    override suspend fun queryPurchases(): List<Purchase> = emptyList()
    override suspend fun purchase(kind: PurchaseKind): List<Purchase> = emptyList()
    override suspend fun restore(): List<Purchase> = emptyList()
    override suspend fun queryPrices(): Map<PurchaseKind, String> =
        if (failPrices) throw RuntimeException("billing unavailable") else prices
}

class FakeEntitlements(isPremium: Boolean = false) : Entitlements {
    override val isPremium: StateFlow<Boolean> = MutableStateFlow(isPremium)
}
