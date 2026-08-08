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
 * One row of the monitoring list: everything it needs to draw itself, and none of the samples.
 *
 * Every row accumulates for as long as the screen is open, whether or not it is the selected
 * [DetailUiState] — see [LiveViewModel.onSample]. [latest]/[min]/[max]/[avg] are snapshot state so
 * that a sample invalidates only the one row's `Text`s that read them, the same discipline (and
 * the same reason) as the old chart's `Readout` — see the KDoc on `Readout` in LiveScreen.kt. They
 * are held in [nativeUnit] — what OBDb reported — and converted to the user's display unit only at
 * draw time, by the same `UnitReadout` call that draws a single sample.
 */
@Stable
class LiveSeries(
    val key: MetricKey,
    val label: String,
    /** What OBDb reported. Null for a signal it declared no unit for. */
    val nativeUnit: ObdUnit?,
    /** A signal spanning 8000 rpm does not want a decimal place; one spanning 5 volts needs one. */
    val decimals: Int,
) {
    var latest: Double? by mutableStateOf(null); internal set
    var min: Double? by mutableStateOf(null); internal set
    var max: Double? by mutableStateOf(null); internal set
    var avg: Double? by mutableStateOf(null); internal set

    private var sum: Double = 0.0
    private var count: Int = 0

    /** Native, unconverted — see the class KDoc for why. */
    internal fun record(native: Double) {
        latest = native
        min = min?.let { minOf(it, native) } ?: native
        max = max?.let { maxOf(it, native) } ?: native
        sum += native
        count += 1
        avg = sum / count
    }
}

/**
 * The one signal currently open full-screen. [plot] is fed samples only while its [series] is the
 * selected one — see [LiveViewModel.onSample] — so only one ring buffer is ever alive at a time.
 *
 * [min]/[max]/[displayUnit] are the plot's fixed y-range, converted exactly like the samples drawn
 * against it — the same reasoning [SeriesSpec] documents, and the reason `:core:units` has both a
 * `convert` and a `convertDelta`.
 */
data class DetailUiState(
    val series: LiveSeries,
    val plot: LivePlotState,
    val min: Float,
    val max: Float,
    val displayUnit: UnitId?,
)

/**
 * Note what is NOT here: the samples. [rows] holds the series' *holders*, which change identity
 * only when the vehicle's available signals change — never when a sample arrives.
 */
data class LiveUiState(
    /** Everything this vehicle can be asked for, from its signalset. */
    val available: List<SeriesOption> = emptyList(),
    /** One holder per [available] entry, in the same order. */
    val rows: List<LiveSeries> = emptyList(),
    val bookmarked: Set<MetricKey> = emptySet(),
    val units: UnitPreferences = UnitPreferences.METRIC,
    /** The signal currently shown full-screen, or null when the list is showing. */
    val detail: DetailUiState? = null,
)

/** One entry in the list / picker. The label is the signal's name, as OBDb gives it. */
data class SeriesOption(val key: MetricKey, val label: String)

sealed interface LiveIntent {
    /** A row was tapped (or a deep link arrived pre-selected): open its detail. */
    data class Select(val key: MetricKey) : LiveIntent
    data object CloseDetail : LiveIntent
    data class ToggleBookmark(val key: MetricKey) : LiveIntent

    /**
     * Dispatched by the screen every time it enters composition — see the `LaunchedEffect` in
     * LiveScreen.kt. Re-asserts "poll everything" to the scheduler even when `available` has not
     * changed, which is the case that matters: `AcquisitionController` reassigns the poller's
     * attention to whichever screen is foreground, so returning here after another screen took
     * over has to say so again, not just the first time this screen ever opened.
     */
    data object ScreenVisible : LiveIntent
}
