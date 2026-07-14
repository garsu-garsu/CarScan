package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.obdb.ObdbCommand
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/**
 * How much of the adapter's budget a command is entitled to.
 *
 * [CRITICAL] means "on screen right now". It is the only class the governor will not
 * slow down, and it is assigned by [PidScheduler.setVisible] as the user scrolls —
 * not by the signalset, which has no idea what the user is looking at.
 */
enum class Priority { CRITICAL, NORMAL, BACKGROUND, ONESHOT }

/** One command in the poll plan. [periodMs] is the *declared* period; the governor may stretch it. */
data class PollEntry(
    val command: ObdbCommand,
    val periodMs: Long,
    val priority: Priority,
)

/**
 * Turns an OBDb command into a poll entry.
 *
 * **`freq` is SECONDS BETWEEN REQUESTS, not hertz.** `0.25` is four times a second and
 * `3600` is hourly. Read as a frequency it would be inverted — `freq = 0.25` would poll
 * 250 times a second instead of 4, spending the whole budget of a clone adapter on one
 * command. This one multiplication is the entire defence against that, so it lives in
 * exactly one place and is pinned by a test.
 */
fun ObdbCommand.pollEntry(priority: Priority = Priority.NORMAL): PollEntry =
    PollEntry(this, (freq * 1000).roundToLong().coerceAtLeast(MIN_PERIOD_MS), priority)

private const val MIN_PERIOD_MS = 10L

/**
 * Everything a dashboard tile can be keyed on for this command: the signal id always,
 * plus the OBDb metric where there is one. This is what [PidScheduler.setVisible] matches
 * against.
 */
fun ObdbCommand.metricKeys(): Set<MetricKey> = buildSet {
    for (signal in signals) {
        add(MetricKey.Signal(signal.id))
        signal.suggestedMetric?.let { add(MetricKey.Metric(it)) }
    }
}

/**
 * What the adapter can actually do, versus what the dashboard is asking of it.
 *
 * This is a product surface, not a debug readout. A €5 clone does 10–20 queries per
 * second in total; eight gauges at 10 Hz need 80. There is no scheduling trick that
 * closes that gap, so the UI has to be able to say *"your adapter: 14 queries/sec —
 * showing 6 tiles at 2 Hz"*. That sentence needs [capacityHz] and [loadHz] to be
 * honest numbers, including when they are embarrassing.
 */
data class PollerHealth(
    /** `1000 / ewmaRttMs` — what this adapter measurably manages. */
    val capacityHz: Double = 0.0,
    /** `Σ 1/periodSec` over active entries, at their **declared** periods — what we are asking for. */
    val loadHz: Double = 0.0,
    val meanRttMs: Double = 0.0,
    /** Scheduled cycles that were skipped because the adapter could not keep up. */
    val dropRatePct: Double = 0.0,
    /** Entries the governor is currently polling slower than they asked for. */
    val stretchedCount: Int = 0,
)

/**
 * Adapter quirks learned at runtime, for the repository to persist.
 *
 * Re-learning these costs a round trip per session on the device that has the fewest
 * to spare, and the answer never changes for a given piece of hardware.
 */
data class AdapterCapabilities(
    /** Cleared for good on the first `?` or `BUFFER FULL` in reply to a frame-count suffix. */
    val expectedFrames: Boolean = true,
)

/** Where the scheduler gets "now" from. A parameter, so tests run on virtual time. */
fun interface PollClock {
    fun nowMs(): Long

    companion object {
        fun monotonic(): PollClock {
            val start = TimeSource.Monotonic.markNow()
            return PollClock { start.elapsedNow().inWholeMilliseconds }
        }
    }
}

/** The governor's constants. Defaults are the ones the tests pin. */
data class PollConfig(
    /** Commands due within this window of each other are candidates for the same AT batch. */
    val affinityWindowMs: Long = 150,
    /** Stretch when demand exceeds this fraction of measured capacity. */
    val targetUtilization: Double = 0.8,
    /** EWMA smoothing for round-trip time. */
    val rttAlpha: Double = 0.2,
    /** More than this many periods late ⇒ skip the missed cycles instead of catching up. */
    val latenessPeriods: Int = 2,
    /** Consecutive NO_DATA / `?` before a command is marked unsupported for this vehicle. */
    val maxConsecutiveFailures: Int = 5,
    /** Idle keep-alive: `ATRV` at 0.2 Hz. */
    val keepAliveIntervalMs: Long = 5_000,
    /** Idle keep-alive when a diagnostic session is open: `3E00` well inside the 5 s S3 timer. */
    val testerPresentIntervalMs: Long = 2_000,
)
