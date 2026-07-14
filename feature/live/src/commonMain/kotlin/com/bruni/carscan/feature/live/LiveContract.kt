package com.bruni.carscan.feature.live

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bruni.carscan.core.designsystem.chart.LivePlotState
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences

/**
 * One charted series: everything the screen needs in order to draw it, and none of the samples.
 *
 * The samples go into [plot], whose ring buffer Compose cannot see — the only observable thing
 * that changes as they arrive is a revision counter that `LivePlot` reads *inside* its draw
 * lambda. That is what stops a 20 Hz chart recomposing 20 times a second, and it can be defeated
 * from out here: put the samples in [LiveUiState] and the whole screen recomposes per sample,
 * chart included, however careful `LivePlot` was.
 *
 * [latest] is the numeric readout, and it *is* snapshot state — one scalar, read by one `Text`.
 * That `Text` may recompose at the full sample rate; it is a few characters. The chart beside it
 * does not.
 */
@Stable
class LiveSeries(
    val key: MetricKey,
    val label: String,
    /** In [displayUnit] — the range converted exactly like the samples drawn against it. */
    val min: Float,
    val max: Float,
    /** What OBDb reported. Null for a signal it declared no unit for. */
    val nativeUnit: ObdUnit?,
    /** What the user reads. Null when this quantity has no display unit and is shown as decoded. */
    val displayUnit: UnitId?,
    val plot: LivePlotState,
) {
    /**
     * The newest reading **as the car reported it**, in [nativeUnit].
     *
     * Native, not converted, because the text beside the chart is rendered by `UnitReadout`, which
     * converts and formats in one call — and that pairing is the point. A screen that converts here
     * and formats later renders `96.6` for a German driver next to a gauge that renders `96,6`.
     * The *plot* is a different matter: its ring buffer holds converted floats, because that is
     * arithmetic rather than display.
     */
    var latest: Double? by mutableStateOf(null)
        internal set
}

/**
 * Note what is NOT here: the samples. [charted] holds the series' *holders*, which change only
 * when the user selects or deselects one — never when a sample arrives.
 */
data class LiveUiState(
    /** Everything this vehicle can be asked for, from its signalset. */
    val available: List<SeriesOption> = emptyList(),
    val charted: List<LiveSeries> = emptyList(),
    val units: UnitPreferences = UnitPreferences.METRIC,
    val history: HistoryUiState? = null,
)

/** One entry in the picker. The label is the signal's name, as OBDb gives it. */
data class SeriesOption(val key: MetricKey, val label: String)

/** A stored trip's trace. 1 Hz, off the columnar rollup — the raw samples do not exist. */
data class HistoryUiState(
    val tripId: String,
    val label: String,
    val displayUnit: UnitId?,
    /** One line per run of consecutive readings. The space between two segments is a gap. */
    val segments: List<List<HistoryPoint>>,
)

sealed interface LiveIntent {
    data class ToggleSeries(val key: MetricKey) : LiveIntent
    data class ShowHistory(val tripId: String, val signalId: String) : LiveIntent
    data object HideHistory : LiveIntent
}
