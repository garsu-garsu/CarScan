package com.bruni.carscan.core.obd

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The only way to talk to an ELM327.
 *
 * **ELM327 is strictly half-duplex: exactly one command may be outstanding, ever.** The
 * adapter answers with a `>` prompt and nothing else delimits responses, so a second write
 * issued before the first response has been read does not merely interleave — it permanently
 * desynchronizes the stream. Every later response is then attributed to the wrong request,
 * and the symptom is not an error but a *plausible wrong number* on a gauge.
 *
 * That is why this interface exists and why [ObdTransport][com.bruni.carscan.core.transport.ObdTransport]
 * is `internal` to this module: nothing above :core:obd can reach the socket, so nothing
 * above :core:obd can break the invariant. Implementations serialize on a single mutex.
 */
interface Exchanger {
    suspend fun exchange(request: ElmRequest): ElmResponse
}

/**
 * One command to the adapter.
 *
 * [ascii] is sent verbatim with a trailing `\r` — an AT command (`ATSH 7E4`) or an OBD
 * request (`010C`, `220101`).
 */
data class ElmRequest(
    val ascii: String,
    /**
     * How many CAN frames the answer will occupy, when known.
     *
     * The ELM327 has no idea when a car has finished answering, so by default it waits out
     * its full timeout on every single request. Appending the frame count (`010C1`) tells it
     * to return the moment it has that many frames — which roughly **doubles throughput on a
     * clone**, and clones are the binding constraint on how many gauges the app can show.
     *
     * Not every adapter accepts it. One `?` and it must be disabled for that adapter and the
     * fact persisted, because re-probing it every session costs a round trip every session.
     */
    val expectedFrames: Int? = null,
    val timeout: Duration = 1.seconds,
    val retries: Int = 1,
)

sealed interface ElmResponse {
    /** [lines] are whitespace-stripped, echo-free, and exclude the `>` prompt. */
    data class Ok(val lines: List<String>, val roundTrip: Duration) : ElmResponse

    data class Err(val kind: ElmErrorKind, val raw: String) : ElmResponse
}

/**
 * How an ELM327 says no.
 *
 * These are matched **before** any attempt to parse the reply as hex — `NO DATA` is otherwise
 * a perfectly good sequence of nibbles, and treating an error string as a payload is how a
 * decoder invents readings out of nothing.
 */
enum class ElmErrorKind {
    /** The ECU did not answer. This is normal — the car does not support that PID. Back off; do not reconnect. */
    NO_DATA,

    /** Transient bus trouble. Retry once, then re-initialize. */
    CAN_ERROR,
    BUS_BUSY,
    BUS_ERROR,
    BUS_INIT,

    /**
     * We interrupted the adapter mid-operation. **This is always a half-duplex bug on our
     * side** — the adapter is telling us we wrote while it was still talking. Log it loudly;
     * it should be unreachable.
     */
    STOPPED,

    /** Ignition off, or the wrong protocol. Actionable by the user; retry with ATSP0. */
    UNABLE_TO_CONNECT,

    /** We overran the adapter's input buffer. Shrink the write chunk and stop using expectedFrames. */
    BUFFER_FULL,

    /** Unsupported AT command. Cache the fact and stop sending it to this adapter. */
    QUESTION_MARK,

    /** Adapter brown-out (bad power on the OBD port). Full re-initialization required. */
    LV_RESET,
    ACT_ALERT,

    /** No `>` prompt within the timeout. */
    TIMEOUT,

    /**
     * A response arrived with no request outstanding, so the prompt stream is no longer
     * trustworthy. Never continue past this — drain, reset the adapter, re-initialize.
     */
    DESYNC,
}
