package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.obd.ElmErrorKind
import com.bruni.carscan.core.obd.ElmRequest
import com.bruni.carscan.core.obd.ElmResponse
import com.bruni.carscan.core.obd.Exchanger
import com.bruni.carscan.core.transport.ObdTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * The smallest thing that turns an [ObdTransport] into an [Exchanger]: frame on the `>`
 * prompt, one command at a time.
 *
 * It stands in for `ElmSession` (owned by another agent, not yet landed) so the scheduler
 * can be driven against the real [com.bruni.carscan.core.transport.fake.ElmEmulator] —
 * which honours the AT state, so a scheduler that forgets `ATSH` gets `NO DATA` here
 * exactly as it would in a car, and which paces itself at a clone's query ceiling, so the
 * governor's numbers are measured rather than asserted into existence.
 */
class EmulatorExchanger(
    private val transport: ObdTransport,
    scope: CoroutineScope,
    private val clock: PollClock,
) : Exchanger {

    private val pending = StringBuilder()
    private val responses = Channel<String>(Channel.UNLIMITED)
    private val halfDuplex = Mutex()

    /** Every command sent, so a test can see what the adapter's budget was actually spent on. */
    val sent = mutableListOf<String>()

    init {
        scope.launch {
            transport.incoming.collect { chunk ->
                for (byte in chunk) {
                    val char = byte.toInt().toChar()
                    if (char == '>') {
                        responses.send(pending.toString())
                        pending.clear()
                    } else {
                        pending.append(char)
                    }
                }
            }
        }
    }

    override suspend fun exchange(request: ElmRequest): ElmResponse = halfDuplex.withLock {
        sent += request.ascii
        val wire = request.ascii + (request.expectedFrames?.toString() ?: "")
        val started = clock.nowMs()
        transport.write("$wire\r".encodeToByteArray())
        val raw = responses.receive()
        val roundTrip = (clock.nowMs() - started).milliseconds

        val lines = raw.split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val fault = lines.firstNotNullOfOrNull { faultOf(it) }
        if (fault != null) ElmResponse.Err(fault, raw) else ElmResponse.Ok(lines, roundTrip)
    }

    /** Errors are matched before anything is read as hex — `NO DATA` is otherwise good nibbles. */
    private fun faultOf(line: String): ElmErrorKind? = when {
        line == "?" -> ElmErrorKind.QUESTION_MARK
        line.startsWith("NO DATA") -> ElmErrorKind.NO_DATA
        line.startsWith("BUFFER FULL") -> ElmErrorKind.BUFFER_FULL
        line.startsWith("CAN ERROR") -> ElmErrorKind.CAN_ERROR
        line.startsWith("BUS BUSY") -> ElmErrorKind.BUS_BUSY
        line.startsWith("STOPPED") -> ElmErrorKind.STOPPED
        line.startsWith("UNABLE TO CONNECT") -> ElmErrorKind.UNABLE_TO_CONNECT
        line.startsWith("LV RESET") -> ElmErrorKind.LV_RESET
        else -> null
    }
}
