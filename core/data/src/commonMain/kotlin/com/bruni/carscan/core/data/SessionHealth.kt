package com.bruni.carscan.core.data

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * What the adapter can actually do, versus what the dashboard is asking of it.
 *
 * A product surface, not a debug readout. A counterfeit ELM327 manages 10–20 queries
 * per second *in total*; eight gauges refreshing at 10 Hz need 80. That is not slow,
 * it is impossible, and no scheduling trick closes the gap — so the app has to be able
 * to say *"your adapter: 14 queries/sec — showing 6 tiles at 2 Hz"* instead of quietly
 * rendering numbers that are seconds old. That sentence needs [capacityHz] and
 * [loadHz] to be honest, including when they are embarrassing.
 *
 * This mirrors `PollerHealth` in :core:obd field for field, deliberately. :core:data
 * declares the port and cannot see :core:obd — the dependency points inwards — so
 * :composeApp maps one to the other at the Koin binding. A trivial mapping is the
 * price of a repository that does not know an ELM327 exists, and that is what lets the
 * emulator drive the whole UI with no adapter and no car.
 */
data class SessionHealth(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    /** `1000 / meanRttMs` — what this adapter measurably manages. */
    val capacityHz: Double = 0.0,
    /** What the visible tiles are asking for, at their declared periods. */
    val loadHz: Double = 0.0,
    val meanRttMs: Double = 0.0,
    /** Scheduled cycles skipped because the adapter could not keep up. */
    val dropRatePct: Double = 0.0,
    /** Tiles the governor is polling more slowly than they asked for. */
    val stretchedCount: Int = 0,
) {
    /**
     * The dashboard is asking for more than the adapter can deliver.
     *
     * Guarded on `capacityHz > 0` because capacity is measured from real round trips
     * and is zero until the first one returns. Reading "not measured yet" as "cannot
     * cope" would flash a warning at every user on every connect — and a warning that
     * always fires is one nobody reads when it finally matters.
     */
    val isOverSubscribed: Boolean
        get() = capacityHz > 0.0 && loadHz > capacityHz
}
