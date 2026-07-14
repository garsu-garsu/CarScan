package com.bruni.carscan.feature.dashboard

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.isBoolean
import com.bruni.carscan.core.model.isText
import com.bruni.carscan.core.model.obdb.ObdbSignal
import com.bruni.carscan.core.vehicle.EffectiveCommand
import com.bruni.carscan.core.vehicle.EffectiveSignalset

/** One offer in the tile picker: a signal this car can actually be asked for. */
data class PickerEntry(
    val key: MetricKey,
    val label: String,
    /** OBDb has not verified this signal for this vehicle. Offer it, but never present it as fact. */
    val experimental: Boolean,
    val defaultMin: Double,
    val defaultMax: Double,
)

/**
 * How a signal is addressed — **exactly** as `PollDecoder` addresses it when it emits a sample.
 *
 * This is not a detail. `EffectiveSignalset.sourcesByKey` indexes every signal under *both*
 * `MetricKey.Signal(id)` and, where OBDb gives it one, `MetricKey.Metric(m)`. Picking the signal
 * id for a signal that has a metric would produce a tile keyed on `Signal("VSS")` while the
 * poller files its samples under `Metric(SPEED)` — a tile that looks perfectly correct and never
 * receives a single reading. Both sides derive the key the same way, from the same field.
 */
private fun ObdbSignal.metricKey(): MetricKey =
    suggestedMetric?.let(MetricKey::Metric) ?: MetricKey.Signal(id)

/** A dial needs a scale. Text (a VIN), a `fmt.map` state, and a boolean have none. */
private fun ObdbSignal.isGaugeable(): Boolean =
    !hidden && fmt.map.isEmpty() && fmt.unit?.isText != true && fmt.unit?.isBoolean != true

/** A fresh tile's end stop when OBDb declares no maximum for the signal. */
private const val FALLBACK_SPAN = 100.0

/**
 * What the picker may offer: the effective signalset, minus what this car has refused to answer.
 *
 * The exclusion is the point. A signalset describes a model range across every trim and option,
 * so a given car says `NO DATA` to a good fraction of it, and the poller strikes those commands
 * off after five consecutive failures. Offering one anyway buys the user a tile that is stale
 * forever — which reads as the app being broken, not as the car lacking the sensor.
 *
 * A signal is only withheld when **every** command that could supply it is unsupported. A 2023
 * EV6 reports state of charge four different ways; losing one of them is not losing the metric.
 */
fun availableTiles(
    signalset: EffectiveSignalset?,
    unsupported: Set<String>,
): List<PickerEntry> {
    if (signalset == null) return emptyList()

    return signalset.commands
        .filter { it.id !in unsupported }
        .flatMap { command -> command.command.signals.map { command to it } }
        .filter { (_, signal) -> signal.isGaugeable() }
        .groupBy { (_, signal) -> signal.metricKey() }
        .map { (key, candidates) -> entry(key, candidates) }
        .sortedBy { it.label }
}

private fun entry(key: MetricKey, candidates: List<Pair<EffectiveCommand, ObdbSignal>>): PickerEntry {
    // Prefer a command OBDb has verified. Reporting `experimental` off the first candidate would
    // flag a signal we are in fact reading from a perfectly ordinary command.
    val (command, signal) = candidates.firstOrNull { !it.first.experimental } ?: candidates.first()
    val fmt = signal.fmt

    return PickerEntry(
        key = key,
        label = signal.name,
        experimental = command.experimental,
        defaultMin = fmt.min,
        defaultMax = fmt.max ?: (fmt.min + FALLBACK_SPAN),
    )
}
