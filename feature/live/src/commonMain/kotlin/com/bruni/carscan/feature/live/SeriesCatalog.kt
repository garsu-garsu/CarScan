package com.bruni.carscan.feature.live

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.isBoolean
import com.bruni.carscan.core.model.isText
import com.bruni.carscan.core.model.obdb.ObdbSignal
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import com.bruni.carscan.core.vehicle.SignalSource

/**
 * What the screen needs to draw one series, read off the vehicle's own signalset.
 *
 * [min] and [max] are `fmt.min`/`fmt.max` — the *signal's* range — in [unit], the unit OBDb
 * declared. Not the range of the samples seen so far: an autoscaling live chart rescales its axis
 * under the user's eyes, and a steady reading looks like it is moving.
 */
data class SeriesSpec(
    val label: String,
    val min: Float,
    val max: Float,
    val unit: ObdUnit?,
)

/**
 * Everything on this vehicle that can be charted, minus what it has stopped answering.
 *
 * A signal is offered under its **metric** where OBDb declares one, and under its **signal id**
 * where it does not — engine RPM has no `suggestedMetric`, which is the whole reason `MetricKey`
 * exists. The signalset indexes such a signal under *both* keys, so offering every key would list
 * speed twice under the same name and leave the user to pick one at random.
 *
 * [unsupported] is command ids (`"7E0.0142"`) the poller has given up on. A signal whose only
 * command is in there is a chart that would never draw a second point.
 */
fun EffectiveSignalset.seriesOptions(unsupported: Set<String>): List<SeriesOption> =
    sourcesByKey.entries
        .filter { (key, sources) ->
            // Skip the id-keyed copy of a signal that also has a metric: same signal, same label.
            val duplicate = key is MetricKey.Signal && sources.any { it.signal.suggestedMetric != null }
            !duplicate && sources.any { it.isChartable(unsupported) }
        }
        .map { (key, sources) -> SeriesOption(key, sources.first().signal.name) }

/** Null when this vehicle has no chartable signal for [key]. */
fun EffectiveSignalset.seriesSpec(key: MetricKey): SeriesSpec? {
    // The first source that can be charted. The signalset deliberately keeps every source — a
    // 2023 EV6 reports state of charge four different ways — and choosing between them is not
    // something a chart can do for the user, so it charts the first the vehicle offers.
    val signal = get(key).firstOrNull { it.isChartable(unsupported = emptySet()) }?.signal ?: return null
    val fmt = signal.fmt
    val max = fmt.max ?: return null

    return SeriesSpec(
        label = signal.name,
        min = fmt.min.toFloat(),
        max = max.toFloat(),
        unit = fmt.unit,
    )
}

/**
 * A line on a chart needs a number and a scale to draw it against.
 *
 * The VIN is text, the MIL is a flag, and a `fmt.map` signal is an enumerated state — none of them
 * is a line. Neither is a signal with no declared `fmt.max`: charting it would mean inventing the
 * scale, and an invented scale is indistinguishable from a real one once there is a line on it.
 */
private fun SignalSource.isChartable(unsupported: Set<String>): Boolean =
    command.id !in unsupported && signal.isNumeric && signal.fmt.max != null

private val ObdbSignal.isNumeric: Boolean
    get() = fmt.map.isEmpty() && fmt.unit?.let { !it.isText && !it.isBoolean } != false
