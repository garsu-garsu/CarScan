package com.bruni.carscan.obd

import carscan.composeapp.generated.resources.Res
import com.bruni.carscan.core.model.obdb.Signalset
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import com.bruni.carscan.core.vehicle.SignalsetParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * The signals every OBD-II car answers, read out of the APK.
 *
 * **A vehicle's OBDb repository does not contain these.** Ford-F-150 ships 116 commands and not
 * one of them is mode 01 — the standard SAE J1979 PIDs (engine RPM, vehicle speed, coolant) live
 * in a repository of their own. So without this asset the app can connect to a car and then have
 * nothing it knows how to ask it, and every gauge is unaddressable.
 *
 * The JSON is an **opaque runtime asset** and stays one: it is CC BY-SA 4.0, and code-generating
 * it into `.kt` would make the generated file an adaptation of BY-SA data and put the app's own
 * source under ShareAlike. See `composeResources/files/obdb/SOURCE.md`, which also carries the
 * attribution the About screen owes a user, because this copy is *distributed*.
 *
 * The vehicle half of the union is empty until there is somewhere to choose a car from — there is
 * no vehicle picker yet, and guessing a manufacturer's signalset produces gauges that are
 * confidently mislabelled. Standard PIDs on every car beats OEM PIDs on the wrong one.
 */
class BundledSignalsetSource(
    /**
     * Inert today and deliberately kept: SAE J1979 declares no year filters, so every one of its
     * commands matches every year. It starts mattering the moment a vehicle's own signalset joins
     * the union, and a `YearFilter` with `from >= to` is an *inverted* range — read as an
     * intersection, half of a Ford F-150's commands vanish without an error.
     */
    private val modelYear: Int,
) : SignalsetSource {

    private val gate = Mutex()
    private var cached: EffectiveSignalset? = null

    @OptIn(ExperimentalResourceApi::class)
    override suspend fun load(): EffectiveSignalset = gate.withLock {
        cached ?: run {
            val json = Res.readBytes(SAE_J1979).decodeToString()
            EffectiveSignalset.of(
                standard = SignalsetParser.parse(json),
                vehicle = Signalset(commands = emptyList()),
                modelYear = modelYear,
            ).also { cached = it }
        }
    }

    private companion object {
        const val SAE_J1979 = "files/obdb/SAEJ1979.json"
    }
}
