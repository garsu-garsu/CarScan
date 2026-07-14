package com.bruni.carscan.feature.live

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.model.obdb.Fmt
import com.bruni.carscan.core.model.obdb.ObdbCommand
import com.bruni.carscan.core.model.obdb.ObdbSignal
import com.bruni.carscan.core.vehicle.EffectiveCommand
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import com.bruni.carscan.core.vehicle.SignalSource

/**
 * One signal on one command.
 *
 * [command] is the header-qualified id the poller reports as unsupported (`"7E0.010C"`), so it is
 * spelled here the same way `EffectiveCommand.id` spells it.
 */
fun signal(
    id: String,
    name: String = id,
    min: Double = 0.0,
    max: Double? = null,
    unit: ObdUnit? = null,
    metric: SuggestedMetric? = null,
    command: String = "7E0.010C",
): SignalSource {
    val (hdr, cmd) = command.split('.')
    return SignalSource(
        command = EffectiveCommand(
            command = ObdbCommand(
                hdr = hdr,
                cmd = mapOf(cmd.substring(0, 2) to cmd.substring(2)),
                freq = 1.0,
                signals = emptyList(),
            ),
            experimental = false,
        ),
        signal = ObdbSignal(
            id = id,
            name = name,
            suggestedMetric = metric,
            fmt = Fmt(len = 16, min = min, max = max, unit = unit),
        ),
    )
}

/**
 * Indexed exactly as `EffectiveSignalset.of` indexes it: every signal under its id, and
 * additionally under its metric where OBDb declares one. A fixture that indexed only one way
 * would hide the de-duplication the catalog has to do.
 */
fun signalset(vararg sources: SignalSource): EffectiveSignalset {
    val byKey = mutableMapOf<MetricKey, MutableList<SignalSource>>()
    for (source in sources) {
        byKey.getOrPut(MetricKey.Signal(source.signal.id)) { mutableListOf() } += source
        source.signal.suggestedMetric?.let {
            byKey.getOrPut(MetricKey.Metric(it)) { mutableListOf() } += source
        }
    }
    return EffectiveSignalset(
        modelYear = 2023,
        commands = sources.map { it.command }.distinct(),
        sourcesByKey = byKey,
    )
}
