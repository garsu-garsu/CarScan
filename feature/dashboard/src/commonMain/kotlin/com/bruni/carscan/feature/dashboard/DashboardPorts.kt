package com.bruni.carscan.feature.dashboard

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock

/**
 * Where the dashboard gets "now" from — **wall time, in the same base a `SensorSample` is stamped
 * with.**
 *
 * `PollClock` draws a hard line between `nowMs()` (monotonic, for scheduling) and `epochMs()`
 * (wall time, for anything a human will ever see), and a sample carries the latter. Staleness
 * compares a sample's age against this clock, so this clock has to be the epoch one. A monotonic
 * clock here would mark every tile permanently stale — silently, on a real car, and never in a
 * test. That is the same seam that was quietly discarding every recorded trip until it was fixed.
 *
 * A port only so that staleness is testable on virtual time.
 *
 * @see com.bruni.carscan.core.model.SensorSample.timestampMs
 */
fun interface DashboardClock {
    fun epochMs(): Long

    companion object {
        fun system() = DashboardClock { Clock.System.now().toEpochMilliseconds() }
    }
}

/**
 * A tile does not go stale because something happened — it goes stale because *nothing* did. No
 * sample arrives to trigger a re-render, so the passage of time has to be a flow of its own.
 */
internal fun tickerFlow(clock: DashboardClock, periodMs: Long = STALENESS_TICK_MS): Flow<Long> =
    flow {
        while (true) {
            emit(clock.epochMs())
            delay(periodMs)
        }
    }

/** Fine enough that a tile dims promptly, coarse enough not to re-render the grid for nothing. */
internal const val STALENESS_TICK_MS: Long = 500
