package com.bruni.carscan.feature.live

import com.bruni.carscan.core.data.SignalSeries

/** One point of a historical trace: [second] since the trip started. */
data class HistoryPoint(val second: Int, val value: Float)

/**
 * The trip's trace, cut at every second the car did not answer for.
 *
 * `SignalSeries.values[t]` is the reading at second `t`, and a second that was never recorded is
 * `NaN` rather than `0` — deliberately, so that a hole in a trip cannot read back as "stationary".
 * That distinction survives only if the hole survives here too: charted as one continuous line,
 * the points either side of a gap become adjacent and the chart draws a straight edge between
 * them, which is a reading the car never gave. So each run of consecutive readings is its own
 * segment, and the caller draws one line per segment.
 */
fun SignalSeries.toSegments(): List<List<HistoryPoint>> {
    val segments = mutableListOf<List<HistoryPoint>>()
    var current = mutableListOf<HistoryPoint>()

    for (second in values.indices) {
        val value = values[second]
        if (value.isNaN()) {
            if (current.isNotEmpty()) {
                segments += current
                current = mutableListOf()
            }
        } else {
            current += HistoryPoint(second, value)
        }
    }
    if (current.isNotEmpty()) segments += current

    return segments
}
