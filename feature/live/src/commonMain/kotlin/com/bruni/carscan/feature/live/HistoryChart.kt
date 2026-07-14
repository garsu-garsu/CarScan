package com.bruni.carscan.feature.live

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bruni.carscan.core.designsystem.theme.LocalNumberFormatter
import com.patrykandpatrick.vico.multiplatform.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.multiplatform.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.multiplatform.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.multiplatform.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.multiplatform.cartesian.data.lineSeries
import com.patrykandpatrick.vico.multiplatform.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.multiplatform.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.multiplatform.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.multiplatform.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.multiplatform.common.Fill

/**
 * A stored trip's trace: Vico, because this is the chart where axes, zoom and tooltips are the
 * whole point — and it is emphatically NOT the live strip, whose per-frame allocations Vico does
 * not survive at 20 Hz.
 *
 * Read off the 1 Hz `trip_series` rollup, never the raw samples: nothing above 1 Hz is ever
 * persisted, so there is nothing else to read.
 *
 * **One Vico series per segment.** A `SignalSeries` is one entry per second with `NaN` where the
 * car answered nothing, and [HistoryUiState.segments] has already cut the trace at those holes.
 * Handed to Vico as a single series, the readings either side of a gap would be adjacent points
 * and it would draw a straight line between them — a reading the car never gave, indistinguishable
 * on screen from one it did.
 */
@Composable
internal fun HistoryChart(
    history: HistoryUiState,
    colour: Color,
    modifier: Modifier = Modifier,
) {
    val producer = remember { CartesianChartModelProducer() }

    // Vico's default formatter writes a number the way Kotlin's `toString` does — with a dot. Six
    // of our eight locales write a comma, so a German user would get an axis of `13.8`s beside a
    // readout of `13,8`s, in the same app, on the same screen.
    //
    // The y values are already in display units (converted in the state mapper), so this formats
    // and does not convert. Converting again here would apply the offset twice.
    val numbers = LocalNumberFormatter.current
    val yFormatter = remember(numbers) {
        CartesianValueFormatter { _, value, _ -> numbers.format(value, decimals = 1) }
    }
    // x is seconds since the trip began. `1,847` seconds is not a time anyone reads; 30:47 is.
    val xFormatter = remember { CartesianValueFormatter { _, value, _ -> formatElapsed(value) } }

    LaunchedEffect(history) {
        if (history.segments.isEmpty()) return@LaunchedEffect
        producer.runTransaction {
            lineSeries {
                history.segments.forEach { segment ->
                    // x is the second since the trip started, so the gaps are real distances on
                    // the axis rather than the segments being butted up against each other.
                    series(x = segment.map { it.second }, y = segment.map { it.value })
                }
            }
        }
    }

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                // LineProvider.series cycles its lines by index, so one Line paints every segment.
                // Without this each segment would come out a different colour, and a gap in the
                // data would read as a change of series.
                lineProvider = LineCartesianLayer.LineProvider.series(
                    LineCartesianLayer.rememberLine(
                        fill = LineCartesianLayer.LineFill.single(Fill(colour)),
                    ),
                ),
            ),
            startAxis = VerticalAxis.rememberStart(valueFormatter = yFormatter),
            bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = xFormatter),
        ),
        modelProducer = producer,
        modifier = modifier,
    )
}

/**
 * Seconds since the trip started, as `m:ss` (or `h:mm:ss` past an hour).
 *
 * Digits and colons only, so there is nothing here to translate and nothing to get wrong per
 * locale — unlike the y axis, which has to go through the locale's number formatter.
 */
internal fun formatElapsed(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${m.pad()}:${s.pad()}" else "$m:${s.pad()}"
}

private fun Long.pad(): String = toString().padStart(2, '0')
