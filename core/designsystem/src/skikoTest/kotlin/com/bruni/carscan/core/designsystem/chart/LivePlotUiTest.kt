package com.bruni.carscan.core.designsystem.chart

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The gate the whole chart design rests on.
 *
 * "We read the revision counter inside the DrawScope, so composition never runs" is a claim
 * until something counts. This composes the real [LivePlot] body, feeds it 30 s of 20 Hz
 * samples against a 60 Hz frame clock, and asserts the composition counter stays flat while
 * the draw counter climbs. Recomposing once per sample is the failure this design exists to
 * prevent, and it is invisible without this test.
 *
 * The composition counter is the `onCompose` seam on the internal [LivePlotContent]; the draw
 * counter is a `drawWithContent` wrapper on the modifier the chart draws into, so neither
 * needs the production API to know it is under test.
 *
 * **In `jvmTest`, not `commonTest`, on purpose** — see `GaugeRenderersUiTest`. The pure half of
 * this gate, which does run on every target, is `LivePlotStateTest`.
 */
@OptIn(ExperimentalTestApi::class)
class LivePlotUiTest {

    @Test
    fun thirtySecondsOfTwentyHertzSamplesRedrawWithoutEverRecomposing() = runComposeUiTest {
        val state = LivePlotState(capacity = 600)
        var compositions = 0
        var draws = 0
        val countComposition: () -> Unit = { compositions++ }

        mainClock.autoAdvance = false
        setContent {
            LivePlotContent(
                state = state,
                min = 0f,
                max = 8000f,
                color = Color.Cyan,
                modifier = Modifier
                    .size(300.dp, 120.dp)
                    .drawWithContent {
                        draws++
                        drawContent()
                    },
                onCompose = countComposition,
            )
        }
        mainClock.advanceTimeByFrame()

        val compositionsAfterFirstFrame = compositions
        val drawsAfterFirstFrame = draws
        assertEquals(1, compositionsAfterFirstFrame, "the chart should compose exactly once")

        // 20 Hz against a 60 Hz frame clock: one sample every third frame, 1800 frames = 30 s.
        var samples = 0
        repeat(1800) { frame ->
            if (frame % 3 == 0) {
                state.push((frame % 8000).toFloat())
                samples++
            }
            mainClock.advanceTimeByFrame()
        }
        assertEquals(600, samples, "the test drove the wrong sample rate")

        assertEquals(
            compositionsAfterFirstFrame,
            compositions,
            "the chart recomposed ${compositions - compositionsAfterFirstFrame} times while " +
                "samples arrived — the revision counter is being read during composition",
        )
        assertTrue(
            draws - drawsAfterFirstFrame >= 500,
            "the chart only redrew ${draws - drawsAfterFirstFrame} times for 600 samples — it " +
                "is not repainting, so a flat composition count proves nothing",
        )
    }

    @Test
    fun anIdleChartDoesNotRepaint() = runComposeUiTest {
        val state = LivePlotState(capacity = 600)
        var draws = 0

        mainClock.autoAdvance = false
        setContent {
            LivePlotContent(
                state = state,
                min = 0f,
                max = 100f,
                color = Color.Cyan,
                modifier = Modifier
                    .size(300.dp, 120.dp)
                    .drawWithContent {
                        draws++
                        drawContent()
                    },
            )
        }
        mainClock.advanceTimeByFrame()
        state.push(50f)
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        val drawsAfterTheSample = draws

        // No new samples: the frame driver ticks, but nothing should repaint.
        repeat(120) { mainClock.advanceTimeByFrame() }

        assertEquals(
            drawsAfterTheSample,
            draws,
            "the chart repainted ${draws - drawsAfterTheSample} times with no new samples — the " +
                "frame driver is invalidating unconditionally and will burn battery at idle",
        )
    }

    @Test
    fun rendersHostileSamplesWithoutCrashing() = runComposeUiTest {
        val state = LivePlotState(capacity = 8)

        mainClock.autoAdvance = false
        setContent {
            LivePlot(
                state = state,
                min = 0f,
                max = 100f,
                color = Color.Cyan,
                modifier = Modifier.size(300.dp, 120.dp),
            )
        }
        mainClock.advanceTimeByFrame()

        // A single sample (no line to draw yet), then out-of-range and non-finite values.
        listOf(50f, Float.NaN, -500f, 5000f, Float.NEGATIVE_INFINITY, 10f).forEach {
            state.push(it)
            mainClock.advanceTimeByFrame()
        }
        mainClock.advanceTimeByFrame()
    }

    @Test
    fun anEmptyChartDrawsNothingRatherThanThrowing() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            LivePlot(
                state = LivePlotState(capacity = 600),
                min = 0f,
                max = 100f,
                color = Color.Cyan,
                modifier = Modifier.size(300.dp, 120.dp),
            )
        }
        mainClock.advanceTimeByFrame()
    }
}
