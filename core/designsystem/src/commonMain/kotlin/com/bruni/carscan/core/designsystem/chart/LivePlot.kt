package com.bruni.carscan.core.designsystem.chart

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.designsystem.gauge.valueToFraction

/**
 * The live strip chart: the last [LivePlotState.capacity] samples, newest at the right edge,
 * redrawn at display rate however fast the samples arrive.
 *
 * Hand-written rather than a chart library: every declarative one allocates per frame, which
 * does not survive 20 Hz. Three things keep this cheap, and all three are load-bearing:
 *
 *  * the samples live in a preallocated [FloatRingBuffer], never a growing list;
 *  * the revision counter is read **inside** the draw lambda, so a new sample invalidates the
 *    draw phase and composition never runs again after the first frame (asserted in
 *    `LivePlotUiTest`);
 *  * a `withFrameNanos` driver coalesces samples into at most one repaint per frame, so the
 *    sample rate and the frame rate are decoupled.
 *
 * [min] and [max] are the fixed y-range — the signal's range, not the window's. An
 * auto-scaling live chart rescales its axis under the user's eyes and makes a steady reading
 * look like it is moving.
 */
@Composable
fun LivePlot(
    state: LivePlotState,
    min: Float,
    max: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
) {
    LivePlotContent(state, min, max, color, modifier, strokeWidth)
}

/**
 * The body of [LivePlot].
 *
 * [onCompose] exists so a test can count the compositions of this scope — the claim that this
 * chart never recomposes is worth nothing unless something counts. It is internal, defaulted,
 * and on the same code path the app runs.
 */
@Composable
internal fun LivePlotContent(
    state: LivePlotState,
    min: Float,
    max: Float,
    color: Color,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
    onCompose: () -> Unit = {},
) {
    onCompose()

    // Allocated once and rewound on every frame. A Path per frame is 20 of them a second.
    val path = remember(state) { Path() }
    val density = LocalDensity.current
    val stroke = remember(strokeWidth, density) {
        Stroke(
            width = with(density) { strokeWidth.toPx() },
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
    }

    // The frame driver. Samples arriving between two frames are collapsed into one repaint,
    // and a frame with no samples does not repaint at all.
    LaunchedEffect(state) {
        while (true) {
            withFrameNanos { }
            state.onFrame()
        }
    }

    Canvas(modifier) {
        // THE line this design turns on: the snapshot read happens in the draw phase, so a new
        // sample invalidates the drawing and not the composition. Hoist it one line up, into
        // the composable body, and the chart recomposes 20 times a second.
        state.revision.let {
            drawSamples(state, min, max, color, path, stroke)
        }
    }
}

private fun DrawScope.drawSamples(
    state: LivePlotState,
    min: Float,
    max: Float,
    color: Color,
    path: Path,
    stroke: Stroke,
) {
    val count = state.samples.size
    if (count < 2) return // one point is not a line

    // Spaced by capacity, not by count, so the trace scrolls in from the right as it fills
    // instead of stretching to fit and making early samples appear to drift.
    val stepX = size.width / (state.capacity - 1).coerceAtLeast(1).toFloat()

    path.rewind()
    for (i in 0 until count) {
        val x = size.width - (count - 1 - i) * stepX
        // valueToFraction clamps, so an out-of-range sample rides the edge of the plot rather
        // than being drawn outside it.
        val y = size.height - valueToFraction(state.samples[i], min, max) * size.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path = path, color = color, style = stroke)
}
