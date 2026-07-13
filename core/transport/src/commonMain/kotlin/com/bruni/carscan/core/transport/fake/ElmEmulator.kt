package com.bruni.carscan.core.transport.fake

import com.bruni.carscan.core.transport.ObdTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The AT state a real ELM327 carries between commands.
 *
 * The emulator honours all of it. That is the difference between an emulator and a
 * lookup table: a lookup table answers `220101` no matter what header you set, so it
 * cannot catch the bug where the poller forgets to send `ATSH 7E4` — which is precisely
 * the bug the emulator exists to catch.
 */
data class ElmAtState(
    val echo: Boolean = true,
    val linefeed: Boolean = false,
    val spaces: Boolean = true,
    val headers: Boolean = false,
    val canAutoFormat: Boolean = true,
    val txHeader: String = "7DF",
    val rxFilter: String? = null,
    val extendedAddress: String? = null,
    val flowControlHeader: String? = null,
    val flowControlData: String? = null,
    val flowControlMode: Int = 0,
    val protocol: Int = 6,
    val autoProtocol: Boolean = true,
    val adaptiveTiming: Int = 1,
    val timeoutHex: String = "32",
)

/** How this particular adapter misbehaves. The defaults are a healthy v1.5 clone. */
data class ElmEmulatorConfig(
    /** Clones lie about this. Feature-detect, never trust it. */
    val identity: String = "ELM327 v1.5",
    /** ATZ takes real time on real hardware, and code that does not wait for it desyncs. */
    val resetSettleTime: Duration = 1.seconds,
    val commandLatency: Duration = 20.milliseconds,
    /** Roughly what a cheap clone manages. `null` for an adapter with no ceiling. */
    val maxExchangesPerSecond: Int? = null,
    /** Force every response to be delivered in chunks of this size. */
    val chunkSize: Int? = null,
    val promptDelay: Duration = Duration.ZERO,
    /** `010C1` — the frame-count suffix that roughly doubles a clone's throughput. */
    val supportsExpectedFrames: Boolean = true,
    val batteryVoltage: String = "12.6V",
    val vin: String = "KMHL14JA1LA123456",
    val milOn: Boolean = false,
    val dtcCount: Int = 0,
)

/**
 * An ELM327 that behaves like one: a state machine over the AT ladder, an ISO-TP
 * framer, and a car underneath it.
 *
 * It is an [ObdTransport], so it drops into any consumer unchanged — the dashboard,
 * the poller and the session never learn there is no adapter.
 *
 * ```
 * val elm = ElmEmulator(clock = ElmClock { testScheduler.currentTime })
 * elm.open()
 * // ATE0, ATH1, ATSH 7E4, ATCRA 7EC, then 220101 -> a multi-frame answer from 7EC
 * ```
 *
 * Two ECUs live on the bus: the engine (`7E8`, reached via header `7DF` or `7E0`,
 * answers SAE J1979 modes 01 and 09) and the Kia battery manager (`7EC`, reached via
 * header `7E4`, answers mode 22 `0101`). Ask the wrong one and you get `NO DATA`,
 * exactly as you would in a car.
 *
 * Values come from [vehicle]; time comes from [clock], never from a wall clock.
 */
class ElmEmulator(
    private val clock: ElmClock = ElmClock.monotonic(),
    private val config: ElmEmulatorConfig = ElmEmulatorConfig(),
    private val vehicle: VehicleStateSource = DrivingSimulator(),
) : ObdTransport {

    private val pipe = ScriptedPipe(::respond, config.chunkSize, config.promptDelay)

    private var state = ElmAtState()
    private var openedAtMs = 0L

    /** When the adapter is free again. Null until it has answered once. */
    private var nextFreeMs: Long? = null

    /** The AT state as it stands now. Assert on it instead of inferring it from output. */
    val atState: ElmAtState get() = state

    override val incoming: Flow<ByteArray> get() = pipe.incoming

    override suspend fun open() {
        openedAtMs = clock.nowMs()
        pipe.open()
    }

    override suspend fun write(bytes: ByteArray) = pipe.write(bytes)

    override suspend fun close() = pipe.close()

    // --- the exchange -------------------------------------------------------

    private suspend fun respond(request: String): WireResponse {
        // Echo is mirrored as the characters arrive, so ATE0 does not suppress its own echo.
        val echoed = state.echo
        val arrivalEol = eol()

        val command = request.trim()
        val canonical = command.uppercase().filterNot { it == ' ' }

        val lines = if (canonical.startsWith("AT")) {
            val body = canonical.removePrefix("AT")
            pace(if (body == "Z" || body == "WS") config.resetSettleTime else Duration.ZERO)
            handleAt(body)
        } else {
            pace()
            handleObd(canonical)
        }

        val eol = eol()
        val wire = StringBuilder()
        if (echoed) wire.append(command).append(arrivalEol)
        lines.forEach { wire.append(it).append(eol) }
        return WireResponse(wire.toString(), "$eol>")
    }

    /**
     * Latency, plus the throughput ceiling. Both are spent on [clock]'s time, and both
     * are inside the pipe's half-duplex lock, so a caller that floods the adapter is
     * slowed down exactly the way a real one would slow it down.
     */
    private suspend fun pace(settle: Duration = Duration.ZERO) {
        val now = clock.nowMs()
        val queued = nextFreeMs?.let { (it - now).coerceAtLeast(0) } ?: 0L
        val wait = maxOf(config.commandLatency.inWholeMilliseconds, queued).milliseconds + settle
        if (wait > Duration.ZERO) delay(wait)

        val interval = config.maxExchangesPerSecond?.let { 1000L / it } ?: 0L
        nextFreeMs = clock.nowMs() + interval
    }

    private fun eol() = if (state.linefeed) "\r\n" else "\r"

    // --- AT -----------------------------------------------------------------

    private fun handleAt(body: String): List<String> = when {
        body == "Z" || body == "WS" -> {
            state = ElmAtState()
            listOf(config.identity)
        }

        body == "D" -> {
            state = ElmAtState()
            listOf("OK")
        }

        body == "I" -> listOf(config.identity)
        body == "RV" -> listOf(config.batteryVoltage)
        body == "DPN" -> listOf(if (state.autoProtocol) "A${hexDigit(state.protocol)}" else hexDigit(state.protocol))

        body == "E0" -> ok { copy(echo = false) }
        body == "E1" -> ok { copy(echo = true) }
        body == "L0" -> ok { copy(linefeed = false) }
        body == "L1" -> ok { copy(linefeed = true) }
        body == "S0" -> ok { copy(spaces = false) }
        body == "S1" -> ok { copy(spaces = true) }
        body == "H0" -> ok { copy(headers = false) }
        body == "H1" -> ok { copy(headers = true) }
        body == "CAF0" -> ok { copy(canAutoFormat = false) }
        body == "CAF1" -> ok { copy(canAutoFormat = true) }

        // ATSP0 is "auto"; it settles on 6 (ISO 15765-4, 11 bit, 500 kbaud), and ATDPN
        // then reports A6 — the leading A is how the consumer knows it was auto-detected.
        body == "SP0" -> ok { copy(autoProtocol = true, protocol = 6) }
        body.startsWith("SP") && body.length == 3 && isHex(body.substring(2)) ->
            ok { copy(autoProtocol = false, protocol = body[2].digitToInt(16)) }

        body == "CRA" -> ok { copy(rxFilter = null) }
        body == "CEA" -> ok { copy(extendedAddress = null) }

        body.startsWith("SH") && isHex(body.substring(2)) -> ok { copy(txHeader = body.substring(2)) }
        body.startsWith("CRA") && isHex(body.substring(3)) -> ok { copy(rxFilter = body.substring(3)) }
        body.startsWith("CEA") && isHex(body.substring(3)) -> ok { copy(extendedAddress = body.substring(3)) }
        body.startsWith("FCSH") && isHex(body.substring(4)) -> ok { copy(flowControlHeader = body.substring(4)) }
        body.startsWith("FCSD") && isHex(body.substring(4)) -> ok { copy(flowControlData = body.substring(4)) }
        body.startsWith("FCSM") && body.length == 5 && body[4].isDigit() ->
            ok { copy(flowControlMode = body[4].digitToInt()) }
        body.startsWith("ST") && isHex(body.substring(2)) -> ok { copy(timeoutHex = body.substring(2)) }
        body.startsWith("AT") && body.length == 3 && body[2].isDigit() ->
            ok { copy(adaptiveTiming = body[2].digitToInt()) }

        else -> listOf(ElmFault.UNKNOWN_COMMAND.wire)
    }

    private inline fun ok(update: ElmAtState.() -> ElmAtState): List<String> {
        state = state.update()
        return listOf("OK")
    }

    // --- OBD ----------------------------------------------------------------

    private fun handleObd(request: String): List<String> {
        var command = request

        // A trailing frame count ("010C1") tells the adapter to return as soon as it has
        // that many frames instead of waiting out ATST. Clones that never learnt it say "?".
        if (command.length == 5 || command.length == 7) {
            if (!config.supportsExpectedFrames) return listOf(ElmFault.UNKNOWN_COMMAND.wire)
            command = command.dropLast(1)
        }
        if (!isHex(command) || command.length % 2 != 0) return listOf(ElmFault.UNKNOWN_COMMAND.wire)

        val ecu = ecuFor(state.txHeader) ?: return noData()

        // ATCRA is a hardware receive filter: a frame from an id it excludes never
        // reaches the host at all, and the request simply times out.
        state.rxFilter?.let { filter ->
            if (!ecu.equals(filter, ignoreCase = true)) return noData()
        }

        val payload = payloadFor(ecu, command) ?: return noData()
        return isoTp(ecu, payload).map(::render)
    }

    private fun ecuFor(header: String): String? = when (header.uppercase()) {
        "7DF", "7E0" -> ENGINE
        "7E4" -> BATTERY
        else -> null
    }

    private fun payloadFor(ecu: String, command: String): List<Int>? {
        val vehicleState = vehicle.stateAt(clock.nowMs() - openedAtMs)
        return when {
            ecu == ENGINE && command.length == 4 && command.startsWith("01") ->
                mode01(command.substring(2), vehicleState)

            ecu == ENGINE && command == "0902" ->
                listOf(0x49, 0x02, 0x01) + config.vin.map { it.code }

            ecu == BATTERY && command == "220101" -> {
                val soc = (vehicleState.socPct * 2).roundToInt().coerceIn(0, 0xFF)
                listOf(0x62, 0x01, 0x01, 0xEF, 0xFB, 0xE7, 0xEF, soc, 0x00)
            }

            else -> null
        }
    }

    private fun mode01(pid: String, car: VehicleState): List<Int>? {
        val header = listOf(0x41, pid.toInt(16))
        return when (pid) {
            // PIDs 01, 05, 0C, 0D and 11 are supported; bit 32 says 21-40 are described too.
            "00" -> header + listOf(0x88, 0x18, 0x80, 0x01)
            "20" -> header + listOf(0x00, 0x02, 0x00, 0x00)
            "01" -> header + listOf(
                (if (config.milOn) 0x80 else 0x00) or config.dtcCount.coerceIn(0, 0x7F),
                0x07, 0x65, 0x04,
            )
            "05" -> header + byte(car.coolantC + 40)
            "0C" -> {
                val quarters = (car.rpm * 4).roundToInt().coerceIn(0, 0xFFFF)
                header + listOf(quarters shr 8, quarters and 0xFF)
            }
            "0D" -> header + byte(car.speedKph)
            "11" -> header + byte(car.throttlePct * 255 / 100)
            "2F" -> header + byte(car.fuelPct * 255 / 100)
            else -> null
        }
    }

    private fun byte(value: Double) = listOf(value.roundToInt().coerceIn(0, 0xFF))

    private fun noData() = listOf(ElmFault.NO_DATA.wire)

    // --- ISO-TP and rendering ------------------------------------------------

    private class CanFrame(val id: String, val bytes: List<Int>)

    private fun isoTp(id: String, payload: List<Int>): List<CanFrame> {
        if (payload.size <= 7) return listOf(CanFrame(id, listOf(payload.size) + payload))

        val frames = mutableListOf<CanFrame>()
        // First frame: 0x1LLL, a 12-bit total length, then six payload bytes.
        frames += CanFrame(
            id,
            listOf(0x10 or ((payload.size shr 8) and 0x0F), payload.size and 0xFF) + payload.take(6),
        )

        var offset = 6
        var sequence = 1
        while (offset < payload.size) {
            val slice = payload.subList(offset, minOf(offset + 7, payload.size))
            // Consecutive frame: 0x2S, then seven bytes, zero-padded. The length in the
            // first frame is what tells the reassembler where the padding starts.
            frames += CanFrame(id, listOf(0x20 or (sequence and 0x0F)) + slice + List(7 - slice.size) { 0x00 })
            offset += 7
            sequence++
        }
        return frames
    }

    private fun render(frame: CanFrame): String {
        val separator = if (state.spaces) " " else ""
        val bytes = frame.bytes.joinToString(separator, transform = ::hexByte)
        if (!state.headers) return bytes
        return frame.id + separator + bytes
    }

    private fun hexByte(value: Int) = value.toString(16).uppercase().padStart(2, '0')

    private fun hexDigit(value: Int) = value.toString(16).uppercase()

    private fun isHex(text: String) = text.isNotEmpty() && text.all { it in HEX_DIGITS }

    private companion object {
        const val ENGINE = "7E8"
        const val BATTERY = "7EC"
        const val HEX_DIGITS = "0123456789ABCDEF"
    }
}
