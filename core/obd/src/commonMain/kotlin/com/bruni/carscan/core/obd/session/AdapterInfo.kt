package com.bruni.carscan.core.obd.session

/**
 * What we found out about this adapter by asking it, rather than by believing it.
 *
 * Persist this. Every field costs at least one round trip to re-derive, and on a clone
 * that manages fifteen queries a second, a handful of extra round trips at the start of
 * every session is the difference between a dashboard that moves and one that stutters.
 *
 * [identity] is recorded and never trusted. Counterfeit adapters answer `ELM327 v2.1` to
 * `ATI` and then reject half of what a real v2.1 does, so every capability below is
 * feature-detected — we send the command and see what comes back.
 */
data class AdapterInfo(
    /** Whatever `ATI` said. A fingerprint for support tickets, not an input to any decision. */
    val identity: String,
    /** A genuine STN (OBDLink): it answered `STI`. A clone says `?`. */
    val isStn: Boolean,
    /** It accepts the `010C1` frame-count suffix — worth roughly 2x throughput on a clone. */
    val supportsExpectedFrames: Boolean,
    /** It accepts `ATCRA`, the hardware receive filter. */
    val supportsRxFilter: Boolean,
    /** It ignored `ATE0` and keeps echoing. Harmless — we strip the echo — but it costs bytes. */
    val echoSuppressionNeeded: Boolean,
    /** Bytes per `write()`. Halved every time the adapter answers `BUFFER FULL`. */
    val maxWriteChunk: Int,
    /** Measured round trip of a real `0100`. The poll scheduler budgets against this. */
    val rttMs: Long,
    /**
     * The protocol number `ATDPN` reported, e.g. 6 for ISO 15765-4 11-bit.
     *
     * Feed it back into [AtStateCache.protocol] before the next `connect()` and the
     * initializer sends `ATSP6` instead of running a protocol search, which on a cold
     * bus can take several seconds.
     */
    val protocolNum: Int?,
)

/**
 * The mutable half of [AdapterInfo].
 *
 * The session keeps updating this while it runs — `BUFFER FULL` retires
 * [supportsExpectedFrames] mid-session — so the immutable [AdapterInfo] is a snapshot
 * taken on demand rather than a value fixed at connect time.
 */
internal class AdapterFacts {
    var identity: String = UNKNOWN
    var isStn: Boolean = false
    var supportsExpectedFrames: Boolean = true
    var supportsRxFilter: Boolean = true
    var echoSuppressionNeeded: Boolean = false
    var maxWriteChunk: Int = DEFAULT_WRITE_CHUNK
    var rttMs: Long = 0
    var protocolNum: Int? = null

    fun snapshot(): AdapterInfo = AdapterInfo(
        identity = identity,
        isStn = isStn,
        supportsExpectedFrames = supportsExpectedFrames,
        supportsRxFilter = supportsRxFilter,
        echoSuppressionNeeded = echoSuppressionNeeded,
        maxWriteChunk = maxWriteChunk,
        rttMs = rttMs,
        protocolNum = protocolNum,
    )

    /** `BUFFER FULL`: we overran its input buffer. Write less, and ask for less. */
    fun shrinkAfterBufferFull() {
        supportsExpectedFrames = false
        maxWriteChunk = (maxWriteChunk / 2).coerceAtLeast(MIN_WRITE_CHUNK)
    }

    internal companion object {
        const val UNKNOWN = "unknown"

        /** The default ATT payload of a BLE adapter, which is the tightest of the three transports. */
        const val DEFAULT_WRITE_CHUNK = 20
        const val MIN_WRITE_CHUNK = 4
    }
}
