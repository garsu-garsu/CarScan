package com.bruni.carscan.core.designsystem.chart

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf

/** 30 s at 20 Hz — the window the live strip shows. */
const val LIVE_PLOT_DEFAULT_CAPACITY: Int = 600

/**
 * The samples behind a [LivePlot], and the one piece of snapshot state that drives its redraw.
 *
 * The design in one sentence: **samples land in a plain array that Compose cannot see, and the
 * only observable thing that changes is [revision], which nothing but the `DrawScope` lambda
 * ever reads.** A chart that instead holds its samples in a `mutableStateListOf`, or that
 * reads a counter from its composable body, recomposes once per sample — 20 times a second,
 * forever — and that is what makes hand-written live charts janky.
 *
 * Redraws are additionally coalesced by the frame clock: [push] only marks the buffer dirty,
 * and [onFrame] — driven from `withFrameNanos` — turns any number of pending samples into at
 * most one redraw per displayed frame. A 100 Hz adapter therefore costs the same as a 20 Hz
 * one, and an idle chart costs nothing at all.
 *
 * Not thread-safe: push from the same dispatcher the chart draws on (the main one).
 */
@Stable
class LivePlotState(val capacity: Int = LIVE_PLOT_DEFAULT_CAPACITY) {

    /**
     * The window, readable so that a consumer can assert on what actually survived eviction.
     * [FloatRingBuffer]'s only mutators are `push` and `clear`, so exposing it for reads gives a
     * caller no way to corrupt the plot — and a feature that cannot see the window cannot test
     * that its ViewModel feeds a bounded one.
     */
    val samples = FloatRingBuffer(capacity)

    /** Samples pushed since the last frame. Deliberately NOT snapshot state. */
    private var pending = 0

    private val _revision = mutableIntStateOf(0)

    /**
     * Bumped once per frame that carried new samples.
     *
     * Read this **inside the `DrawScope` lambda**. Reading it from a composable body — or
     * hoisting it into a `remember` key — subscribes the composition instead of the draw
     * phase and reintroduces the per-sample recomposition this class exists to avoid.
     */
    val revision: Int get() = _revision.intValue

    /**
     * Records a sample. Non-finite values are dropped: a `NaN` from a failed decode would
     * otherwise become a `NaN` coordinate, and a path with one bad point does not draw at all.
     */
    fun push(value: Float) {
        if (!value.isFinite()) return
        samples.push(value)
        pending++
    }

    fun clear() {
        samples.clear()
        pending = 0
        _revision.intValue++
    }

    /** Called once per display frame. Invalidates the draw phase only if a sample arrived. */
    internal fun onFrame() {
        if (pending == 0) return
        pending = 0
        _revision.intValue++
    }
}
