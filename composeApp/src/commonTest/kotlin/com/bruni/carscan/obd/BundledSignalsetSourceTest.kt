package com.bruni.carscan.obd

import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.SignalsetAvailability
import com.bruni.carscan.core.data.SignalsetProvider
import com.bruni.carscan.core.data.ThemeMode
import com.bruni.carscan.core.data.Vehicle
import com.bruni.carscan.core.data.VehicleRepository
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.SpeedUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private const val STANDARD_JSON = """
{"commands": [
  {"hdr": "7E0", "cmd": {"01": "0C"}, "freq": 1, "signals": [
    {"id": "RPM", "name": "Engine RPM", "fmt": {"len": 16}}
  ]}
]}
"""

/** A minimal, hand-trimmed excerpt of the real `Kia-EV6` signalset — one vehicle-only command. */
private const val KIA_EV6_JSON = """
{"commands": [
  {"hdr": "744", "rax": "74C", "cmd": {"22": "E003"}, "freq": 1, "signals": [
    {
      "id": "EV6_HVBAT_SOC_VCMS",
      "name": "HV battery charge",
      "suggestedMetric": "stateOfCharge",
      "fmt": {"bix": 120, "len": 8, "max": 100, "div": 2, "unit": "percent"}
    }
  ]}
]}
"""

/**
 * [BundledSignalsetSource] widened to load the active vehicle's OBDb signalset: what the union
 * contains when a vehicle is picked, what it falls back to when one is not, and the caching-seam
 * regression this project keeps producing (see the class KDoc).
 */
class BundledSignalsetSourceTest {

    @Test
    fun `active vehicle with a bundled obdbRepo unions its signalset with the standard one`() = runTest {
        val settings = FakeSettingsRepository(activeVehicleId = "v1")
        val vehicles = FakeVehicleRepository(
            "v1" to Vehicle(id = "v1", obdbRepo = "Kia-EV6", modelYear = 2023, createdMs = 0),
        )
        val source = BundledSignalsetSource(
            modelYear = 2020,
            settings = settings,
            vehicles = vehicles,
            provider = FakeSignalsetProvider("Kia-EV6" to KIA_EV6_JSON),
            readAsset = assetsOf("files/obdb/SAEJ1979.json" to STANDARD_JSON),
        )

        val loaded = source.load()

        loaded.sourcesOf("RPM") shouldHaveSize 1
        loaded.sourcesOf("EV6_HVBAT_SOC_VCMS") shouldHaveSize 1
        loaded.modelYear shouldBe 2023
    }

    @Test
    fun `no active vehicle falls back to standard-only`() = runTest {
        val settings = FakeSettingsRepository(activeVehicleId = null)
        val vehicles = FakeVehicleRepository()
        val source = BundledSignalsetSource(
            modelYear = 2020,
            settings = settings,
            vehicles = vehicles,
            provider = FakeSignalsetProvider("Kia-EV6" to KIA_EV6_JSON),
            readAsset = assetsOf("files/obdb/SAEJ1979.json" to STANDARD_JSON),
        )

        val loaded = source.load()

        loaded.sourcesOf("RPM") shouldHaveSize 1
        loaded.sourcesOf("EV6_HVBAT_SOC_VCMS").shouldBeEmpty()
        loaded.modelYear shouldBe 2020
    }

    @Test
    fun `an obdbRepo with no bundled asset falls back to standard-only`() = runTest {
        val settings = FakeSettingsRepository(activeVehicleId = "v1")
        val vehicles = FakeVehicleRepository(
            "v1" to Vehicle(id = "v1", obdbRepo = "Some-Unbundled-Repo", modelYear = 2023, createdMs = 0),
        )
        val source = BundledSignalsetSource(
            modelYear = 2020,
            settings = settings,
            vehicles = vehicles,
            provider = FakeSignalsetProvider(),
            readAsset = assetsOf("files/obdb/SAEJ1979.json" to STANDARD_JSON),
        )

        val loaded = source.load()

        loaded.sourcesOf("RPM") shouldHaveSize 1
        loaded.modelYear shouldBe 2020
    }

    /**
     * The caching-seam regression: the old implementation cached the union forever behind a
     * `Mutex`, so a user who switched cars and reconnected kept reading the OLD one. `load()` must
     * read the CURRENT active vehicle on every call.
     */
    @Test
    fun `changing the active vehicle between two loads changes what load returns`() = runTest {
        val settings = FakeSettingsRepository(activeVehicleId = "v1")
        val vehicles = FakeVehicleRepository(
            "v1" to Vehicle(id = "v1", obdbRepo = "Kia-EV6", modelYear = 2023, createdMs = 0),
        )
        val source = BundledSignalsetSource(
            modelYear = 2020,
            settings = settings,
            vehicles = vehicles,
            provider = FakeSignalsetProvider("Kia-EV6" to KIA_EV6_JSON),
            readAsset = assetsOf("files/obdb/SAEJ1979.json" to STANDARD_JSON),
        )

        val first = source.load()
        first.sourcesOf("EV6_HVBAT_SOC_VCMS") shouldHaveSize 1

        settings.setActiveVehicleId(null)
        val second = source.load()

        second.sourcesOf("EV6_HVBAT_SOC_VCMS").shouldBeEmpty()
    }
}

private fun assetsOf(vararg pairs: Pair<String, String>): suspend (String) -> String {
    val assets = pairs.toMap()
    return { path -> assets[path] ?: error("No fake asset at $path") }
}

private class FakeSettingsRepository(activeVehicleId: String?) : SettingsRepository {
    private val state = MutableStateFlow(Settings(activeVehicleId = activeVehicleId))
    override val settings: Flow<Settings> = state

    override suspend fun setRecordTrips(enabled: Boolean) = Unit
    override suspend fun setUnit(quantity: Quantity, unit: UnitId) = Unit
    override suspend fun setUnits(units: UnitPreferences) = Unit

    @Deprecated("Use setUnit(Quantity.SPEED, …).", ReplaceWith("setUnit(Quantity.SPEED, unit)"))
    override suspend fun setSpeedUnit(unit: SpeedUnit) = Unit
    override suspend fun setKeepScreenOn(enabled: Boolean) = Unit

    override suspend fun setActiveVehicleId(id: String?) {
        state.value = state.value.copy(activeVehicleId = id)
    }

    override suspend fun setThemeMode(mode: ThemeMode) = Unit
    override suspend fun setGaugeStyle(style: String) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit
    override suspend fun setAcquisitionSource(source: AcquisitionSource) = Unit
    override suspend fun setAutoDriveDetectSpeedKmh(kmh: Int) = Unit
    override suspend fun setBackgroundTracking(enabled: Boolean) = Unit
}

private class FakeVehicleRepository(vararg vehicles: Pair<String, Vehicle>) : VehicleRepository {
    private val byId = vehicles.toMap()

    override suspend fun all(): List<Vehicle> = byId.values.toList()
    override suspend fun byId(id: String): Vehicle? = byId[id]
    override suspend fun byVin(vin: String): Vehicle? = byId.values.firstOrNull { it.vin == vin }
    override suspend fun remember(vehicle: Vehicle) = Unit
    override suspend fun touchLastConnected(id: String, atMs: Long) = Unit
    override suspend fun forget(id: String) = Unit
}

/** Stands in for [DefaultSignalsetProvider]: the union logic under test only needs the cached-JSON
 * half of the port, keyed by repo, with no bundled-asset or network machinery behind it. */
private class FakeSignalsetProvider(vararg cachedJson: Pair<String, String>) : SignalsetProvider {
    private val byRepo = cachedJson.toMap()

    override suspend fun cachedJson(repo: String): String? = byRepo[repo]
    override suspend fun ensureAvailable(repo: String): SignalsetAvailability = SignalsetAvailability.AVAILABLE
}
