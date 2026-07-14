package com.bruni.carscan.core.obd.session

import com.bruni.carscan.core.obd.ElmRequest
import com.bruni.carscan.core.obd.ElmResponse
import kotlinx.coroutines.delay

/** The adapter never got far enough to be usable. */
class ElmInitFailure(message: String) : Exception(message)

/**
 * The session's own door into itself.
 *
 * The initializer runs *inside* the half-duplex lock — it is a long sequence of commands
 * that must not be interleaved with a poll — so it cannot go through the public
 * [com.bruni.carscan.core.obd.Exchanger], which takes that lock. It talks through this
 * instead, which is the same exchange with the same retry policy and no lock.
 */
internal interface ElmIo {
    suspend fun exchange(request: ElmRequest): ElmResponse

    /** Read and discard whatever the adapter is still saying. */
    suspend fun drain()

    /** Whether the last response carried an echo of its command. See [ElmReply.echoed]. */
    val lastEchoed: Boolean

    val facts: AdapterFacts
}

/**
 * The init ladder, and the feature detection that has to happen alongside it.
 *
 * Every rung is here because leaving it out breaks something specific:
 *
 *  - `ATZ` is a hardware reset, and a clone is still booting when it answers. Write the
 *    next command into that window and it is dropped, silently, and the session begins
 *    one response out of step.
 *  - `ATE0` stops the adapter echoing. Half of the cheap ones ignore it, so we detect
 *    that and strip the echo ourselves rather than trusting the setting took.
 *  - `ATS0` removes the spaces from the hex. That is about 30% of the bytes on the wire,
 *    and on a Bluetooth LE link the bytes *are* the bottleneck.
 *  - `ATH1` turns headers **on**. This is the opposite of what most OBD code does, and it
 *    is deliberate: we do our own ISO-TP reassembly and we demultiplex several ECUs
 *    answering the same broadcast. Without the CAN id on every line, two ECUs answering
 *    `7DF` are indistinguishable and their frames interleave into nonsense.
 *  - the protocol is searched for exactly once and then cached, because the search costs
 *    seconds and the answer never changes for a given car.
 *
 * And the thing the whole ladder is built around: **`ATI` is a lie.** A counterfeit
 * adapter reports `ELM327 v2.1` and then rejects `ATCRA` and the `010C1` frame-count
 * suffix that a v2.1 must support. So nothing branches on the version string. Every
 * capability is established by sending the command and reading the answer.
 */
internal class ElmInitializer(
    private val io: ElmIo,
    private val config: ElmSessionConfig,
) {

    /** The full ladder, from a cold adapter to one that has just answered a real `0100`. */
    suspend fun initialize(cache: AtStateCache) {
        reset("ATZ")
        ladder()
        identify()
        protocol(cache)
        io.exchange(ElmRequest(ADAPTIVE_TIMING))
        probe()
    }

    /**
     * `ATWS`, then put back everything `ATWS` threw away.
     *
     * A warm start resets the adapter's AT registers to defaults. If we resume without
     * replaying them, the very next command goes out with headers off and the default
     * broadcast header — and it *answers*, with a frame we will happily attribute to the
     * ECU we thought we were addressing.
     */
    suspend fun recover(cache: AtStateCache) {
        reset("ATWS")
        ladder()
        io.facts.protocolNum?.let { io.exchange(ElmRequest("ATSP${hexDigit(it)}")) }
        io.exchange(ElmRequest(ADAPTIVE_TIMING))
        replay(cache)
    }

    /** A hardware reset, and then the wait that no datasheet mentions. */
    private suspend fun reset(command: String) {
        io.drain()
        val response = io.exchange(
            ElmRequest(command, timeout = config.resetTimeout, retries = RESET_RETRIES),
        )
        if (response is ElmResponse.Err) {
            throw ElmInitFailure("the adapter did not answer $command: ${response.kind}")
        }
        // It answers before it has finished booting. A command written into that window is
        // swallowed without a trace, and the session starts one response behind — which is
        // indistinguishable from working until a gauge shows the wrong number.
        delay(config.resetSettle)
        io.drain()
    }

    private suspend fun ladder() {
        io.exchange(ElmRequest("ATE0"))

        // Whether ATE0 took cannot be read from ATE0's own reply: an ELM327 mirrors
        // characters as they arrive, so it echoes its own name whatever it then does with
        // the setting. The *next* command is the one that tells you.
        io.exchange(ElmRequest("ATL0"))
        io.facts.echoSuppressionNeeded = io.lastEchoed

        io.exchange(ElmRequest("ATS0"))
        io.exchange(ElmRequest("ATH1"))
        io.exchange(ElmRequest("ATCAF1"))
    }

    private suspend fun identify() {
        val identity = io.exchange(ElmRequest("ATI"))
        io.facts.identity =
            (identity as? ElmResponse.Ok)?.lines?.firstOrNull() ?: AdapterFacts.UNKNOWN

        // The one honest question you can ask an adapter. A clone says `?`; only a genuine
        // STN answers, because only a genuine STN has the command.
        io.facts.isStn = io.exchange(ElmRequest("STI")) is ElmResponse.Ok

        // Feature-detect the hardware receive filter with its bare form, which doubles as
        // clearing whatever filter the last session left behind.
        io.facts.supportsRxFilter = io.exchange(ElmRequest("ATCRA")) is ElmResponse.Ok
    }

    private suspend fun protocol(cache: AtStateCache) {
        val known = cache.protocol
        if (known != null && io.exchange(ElmRequest("ATSP${hexDigit(known)}")) is ElmResponse.Ok) {
            io.facts.protocolNum = known
            return
        }

        io.exchange(ElmRequest("ATSP0"))
        // ATSP0 on its own detects nothing — the adapter only searches when it has
        // something to send. A real request is what forces the search; ATDPN then reads
        // back whatever it settled on.
        io.exchange(ElmRequest(PROBE, timeout = config.searchTimeout))

        val reported = io.exchange(ElmRequest("ATDPN"))
        val number = (reported as? ElmResponse.Ok)?.lines?.firstOrNull()
            ?.removePrefix("A") // "A6": the A means it was auto-detected rather than set
            ?.toIntOrNull(16)
        io.facts.protocolNum = number
        cache.protocol = number
    }

    /**
     * One real request, for two answers at once: the car is talking to us, and this is what
     * a round trip actually costs. The frame-count suffix rides along, so an adapter that
     * cannot do it says so here rather than on the first gauge the user looks at.
     */
    private suspend fun probe() {
        val response = io.exchange(
            ElmRequest(PROBE, expectedFrames = 1, timeout = config.searchTimeout),
        )
        if (response is ElmResponse.Ok) {
            io.facts.rttMs = response.roundTrip.inWholeMilliseconds
        }
    }

    private suspend fun replay(cache: AtStateCache) {
        cache.header?.let { io.exchange(ElmRequest("ATSH $it")) }
        cache.rxFilter?.let { io.exchange(ElmRequest("ATCRA $it")) }
        cache.extAddr?.let { io.exchange(ElmRequest("ATCEA $it")) }
        cache.fc?.let { fc ->
            io.exchange(ElmRequest("ATFCSH ${fc.header}"))
            io.exchange(ElmRequest("ATFCSD ${fc.data}"))
            io.exchange(ElmRequest("ATFCSM${fc.mode}"))
        }
        cache.stTimeout?.let {
            io.exchange(ElmRequest("ATAT0"))
            io.exchange(ElmRequest("ATST ${hexByte(it)}"))
        }

        // The reset did not touch the ECUs, but a UDS session times out on its own and we
        // have no idea how long the recovery took. Re-enter it rather than assume it held.
        cache.ecuSession.clear()
    }

    private fun hexDigit(value: Int) = value.toString(16).uppercase()

    private fun hexByte(value: Int) = value.toString(16).uppercase().padStart(2, '0')

    private companion object {
        const val ADAPTIVE_TIMING = "ATAT1"

        /** Mandatory on every OBD-II car, so a car that will not answer it will not answer anything. */
        const val PROBE = "0100"
        const val RESET_RETRIES = 2
    }
}
