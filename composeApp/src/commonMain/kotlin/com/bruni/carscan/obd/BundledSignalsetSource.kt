package com.bruni.carscan.obd

import carscan.composeapp.generated.resources.Res
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.VehicleRepository
import com.bruni.carscan.core.model.obdb.Signalset
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import com.bruni.carscan.core.vehicle.SignalsetParser
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * The signals every OBD-II car answers, read out of the APK, unioned with the signalset of
 * whichever vehicle the user has picked in the garage.
 *
 * **A vehicle's OBDb repository does not contain the standard half.** Ford-F-150 ships 116
 * commands and not one of them is mode 01 — the standard SAE J1979 PIDs (engine RPM, vehicle
 * speed, coolant) live in a repository of their own. So without that asset the app can connect to
 * a car and then have nothing it knows how to ask it, and every gauge is unaddressable.
 *
 * The JSON is an **opaque runtime asset** and stays one: it is CC BY-SA 4.0, and code-generating
 * it into `.kt` would make the generated file an adaptation of BY-SA data and put the app's own
 * source under ShareAlike. See `composeResources/files/obdb/SOURCE.md`, which also carries the
 * attribution the About screen owes a user, because these copies are *distributed*.
 *
 * The vehicle half of the union comes from [SettingsRepository.settings]`.activeVehicleId` →
 * [VehicleRepository.byId] → `Vehicle.obdbRepo` → `files/obdb/<obdbRepo>.json`. No active vehicle,
 * no `obdbRepo`, or a repo with no bundled asset all fall back to standard-only — a signalset
 * guessed wrong produces gauges that are confidently mislabelled, which is worse than no gauges.
 */
class BundledSignalsetSource(
    /**
     * The model year assumed when the active vehicle (if any) does not say one. SAE J1979
     * declares no year filters, so every one of its commands matches every year regardless — this
     * only starts mattering for the vehicle half of the union, where a `YearFilter` with
     * `from >= to` is an *inverted* range and half of a Ford F-150's commands vanish without an
     * error.
     */
    private val modelYear: Int,
    private val settings: SettingsRepository,
    private val vehicles: VehicleRepository,
    /**
     * Reads a bundled asset by its `composeResources`-relative path. Injected so a test can supply
     * fake JSON without compose resources on the test classpath; production uses [readComposeAsset].
     */
    private val readAsset: suspend (path: String) -> String = ::readComposeAsset,
) : SignalsetSource {

    /**
     * No cache. `load()` is called once per connect, which is cheap, and the alternative — a
     * cache that outlives a vehicle change — is the bug this project keeps producing: a user picks
     * a different car, reconnects, and a forever-cache hands back the OLD union. Every call reads
     * the CURRENT active vehicle.
     */
    override suspend fun load(): EffectiveSignalset {
        val standard = SignalsetParser.parse(readAsset(SAE_J1979))

        val activeVehicleId = settings.settings.first().activeVehicleId
        val vehicle = activeVehicleId?.let { vehicles.byId(it) }
        val obdbRepo = vehicle?.obdbRepo

        val vehicleSignalset = obdbRepo?.let { repo ->
            runCatching { SignalsetParser.parse(readAsset("files/obdb/$repo.json")) }.getOrNull()
        }

        return EffectiveSignalset.of(
            standard = standard,
            vehicle = vehicleSignalset ?: Signalset(commands = emptyList()),
            modelYear = vehicleSignalset?.let { vehicle.modelYear?.toInt() } ?: modelYear,
        )
    }

    private companion object {
        const val SAE_J1979 = "files/obdb/SAEJ1979.json"
    }
}

@OptIn(ExperimentalResourceApi::class)
private suspend fun readComposeAsset(path: String): String = Res.readBytes(path).decodeToString()
