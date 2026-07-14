package com.bruni.carscan.core.designsystem.chart

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chart's whole design is "samples arrive at 20 Hz, composition runs zero times". These
 * tests hold that claim up.
 *
 * What they prove: pushing a sample invalidates only a reader of [LivePlotState.revision]
 * (the `DrawScope` lambda), never a reader of the properties a composable body reads; and the
 * frame driver coalesces a burst of samples into at most one redraw per frame.
 *
 * What they cannot prove: that the real `LivePlot` composable body does not itself read
 * `revision`. That needs a composition, i.e. `runComposeUiTest`.
 */
class LivePlotStateTest {

    private object CompositionScope
    private object DrawScope

    @Test
    fun aFrameWithPendingSamplesBumpsTheRevisionExactlyOnce() {
        val state = LivePlotState(capacity = 600)
        val before = state.revision

        repeat(20) { state.push(it.toFloat()) }
        state.onFrame()

        assertEquals(
            before + 1,
            state.revision,
            "20 samples in one frame interval must coalesce into a single redraw",
        )
    }

    @Test
    fun anIdleFrameDoesNotBumpTheRevision() {
        val state = LivePlotState(capacity = 600)
        state.push(1f)
        state.onFrame()
        val afterFirstDraw = state.revision

        repeat(100) { state.onFrame() }

        assertEquals(
            afterFirstDraw,
            state.revision,
            "a frame with no new samples must not invalidate the draw phase",
        )
    }

    @Test
    fun samplesAreVisibleToTheDrawPhaseInOrder() {
        val state = LivePlotState(capacity = 3)
        listOf(1f, 2f, 3f, 4f).forEach(state::push)

        assertEquals(3, state.samples.size)
        assertEquals(listOf(2f, 3f, 4f), (0 until state.samples.size).map { state.samples[it] })
    }

    @Test
    fun nonFiniteSamplesAreDroppedRatherThanBreakingThePath() {
        val state = LivePlotState(capacity = 4)
        state.push(1f)
        state.push(Float.NaN)
        state.push(Float.POSITIVE_INFINITY)
        state.push(2f)

        assertEquals(2, state.samples.size)
        assertEquals(listOf(1f, 2f), (0 until state.samples.size).map { state.samples[it] })
    }

    @Test
    fun aDroppedSampleDoesNotScheduleARedraw() {
        val state = LivePlotState(capacity = 4)
        val before = state.revision

        state.push(Float.NaN)
        state.onFrame()

        assertEquals(before, state.revision)
    }

    /**
     * The gate. 20 Hz for 30 s on a 60 Hz display: 600 samples, 1800 frames.
     *
     * The draw scope reads `revision` and must be invalidated once per frame that carried a
     * sample. The composition scope reads what a composable body reads and must never be
     * invalidated at all — a single invalidation here is a recomposition per sample, which is
     * exactly the bug this design exists to prevent.
     */
    @Test
    fun thirtySecondsOfTwentyHertzSamplesInvalidatesTheDrawPhaseAndNeverTheComposition() {
        val state = LivePlotState(capacity = 600)
        var compositionInvalidations = 0
        var drawInvalidations = 0

        val observer = SnapshotStateObserver { runnable -> runnable() }
        observer.start()
        try {
            // What a composable body reads: configuration, never per-sample state.
            observer.observeReads(CompositionScope, { compositionInvalidations++ }) {
                state.capacity
            }

            var frames = 0
            var samples = 0
            repeat(1800) { frame ->
                // 20 Hz against a 60 Hz frame clock: a sample every third frame.
                if (frame % 3 == 0) {
                    state.push(frame.toFloat())
                    samples++
                }
                state.onFrame()
                Snapshot.sendApplyNotifications()

                // A real draw phase re-reads its state each time it is invalidated.
                observer.observeReads(DrawScope, { drawInvalidations++ }) {
                    state.revision
                }
                frames++
            }

            assertEquals(600, samples, "test drove the wrong sample rate")
            assertEquals(1800, frames)
            assertEquals(
                0,
                compositionInvalidations,
                "composition was invalidated $compositionInvalidations times — the chart is " +
                    "recomposing per sample",
            )
            assertTrue(
                drawInvalidations >= 599,
                "the draw phase was invalidated only $drawInvalidations times for 600 samples " +
                    "— the chart is not redrawing",
            )
        } finally {
            observer.stop()
            observer.clear()
        }
    }
}
