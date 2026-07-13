package com.bruni.carscan.core.transport.fake

import com.bruni.carscan.core.transport.ObdTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/** What the adapter sends back for one command. */
sealed interface FakeReply {
    /** Ordinary lines, decorated with echo and prompt according to the transport's settings. */
    data class Lines(val lines: List<String>) : FakeReply

    /** One of the ELM error tokens. */
    data class Fault(val fault: ElmFault) : FakeReply

    /** Exactly these characters, with no echo and no prompt. The escape hatch for malformed output. */
    data class Raw(val wire: String) : FakeReply

    /** Nothing at all — the adapter has stopped answering. Use this to test timeouts. */
    data object Silence : FakeReply
}

/**
 * What one command answers with. Replies are consumed in the order they were declared
 * and the last one repeats forever, so `on("010C") fail BUFFER_FULL respond "..."`
 * scripts a failure followed by a successful retry.
 */
class FakeRule internal constructor(internal val request: String) {
    private val replies = mutableListOf<FakeReply>()
    private var index = 0

    infix fun respond(line: String): FakeRule = add(FakeReply.Lines(listOf(line)))

    infix fun respondLines(lines: List<String>): FakeRule = add(FakeReply.Lines(lines))

    infix fun fail(fault: ElmFault): FakeRule = add(FakeReply.Fault(fault))

    infix fun raw(wire: String): FakeRule = add(FakeReply.Raw(wire))

    fun silence(): FakeRule = add(FakeReply.Silence)

    fun respondEach(vararg reply: FakeReply): FakeRule {
        replies += reply
        return this
    }

    private fun add(reply: FakeReply): FakeRule {
        replies += reply
        return this
    }

    internal fun next(): FakeReply {
        if (replies.isEmpty()) return FakeReply.Fault(ElmFault.NO_DATA)
        return replies[minOf(index++, replies.lastIndex)]
    }
}

/** The knobs. Every one of them reproduces something a real cheap adapter actually does. */
class FakeObdTransportBuilder internal constructor() {
    /** The adapter ignores ATE0 and keeps echoing. */
    var echo: Boolean = false

    /** ATL1 behaviour: lines end CR LF rather than CR. */
    var linefeed: Boolean = false

    /** Some adapters simply forget the prompt under load, and the consumer must not hang forever. */
    var emitPrompt: Boolean = true

    /** The prompt arrives late, in its own chunk, after the payload. */
    var promptDelay: Duration = Duration.ZERO

    /** Deliver every response in chunks of this size. Set to 1 for the worst case. */
    var chunkSize: Int? = null

    /** Time from command to first byte of the answer. Virtual time under `runTest`. */
    var latency: Duration = Duration.ZERO

    /** Characters spat out ahead of the response — the desync trigger. */
    var garbageBefore: String? = null

    /** What an unscripted command answers with. */
    var fallback: FakeReply = FakeReply.Fault(ElmFault.NO_DATA)

    internal val rules = mutableListOf<FakeRule>()

    fun on(request: String): FakeRule = FakeRule(normalizeElmRequest(request)).also { rules += it }
}

/** Case, spaces and line endings are noise; an ELM327 ignores all three. */
internal fun normalizeElmRequest(request: String): String =
    request.filterNot { it == ' ' || it == '\r' || it == '\n' }.uppercase()

/**
 * A scripted adapter with fault injection — the workhorse every other module tests against.
 *
 * ```
 * val transport = FakeObdTransport {
 *     chunkSize = 1                                   // one byte at a time
 *     latency = 20.milliseconds
 *     echo = true                                     // it ignores ATE0
 *     on("010C") respond "7E8 04 41 0C 1A F8"
 *     on("0100") fail ElmFault.BUFFER_FULL respond "7E8 06 41 00 88 18 80 01"
 * }
 * ```
 *
 * For a scripted response use this; for something that behaves like an adapter — one
 * that honours the AT state you set and answers with values that move — use [ElmEmulator].
 */
class FakeObdTransport(configure: FakeObdTransportBuilder.() -> Unit = {}) : ObdTransport {

    private val spec = FakeObdTransportBuilder().apply(configure)
    private val byRequest = spec.rules.associateBy { it.request }
    private val pipe = ScriptedPipe(::respond, spec.chunkSize, spec.promptDelay)

    /** Every command written to this transport, normalized, in order. */
    val requests: List<String> get() = pipe.requests

    override val incoming: Flow<ByteArray> get() = pipe.incoming

    override suspend fun open() = pipe.open()

    override suspend fun write(bytes: ByteArray) = pipe.write(bytes)

    override suspend fun close() = pipe.close()

    /** Push unsolicited bytes at the consumer, with no command to explain them. */
    suspend fun inject(raw: String) = pipe.inject(raw)

    private suspend fun respond(request: String): WireResponse? {
        if (spec.latency > Duration.ZERO) delay(spec.latency)

        val reply = byRequest[normalizeElmRequest(request)]?.next() ?: spec.fallback
        if (reply is FakeReply.Silence) return null
        if (reply is FakeReply.Raw) return WireResponse(reply.wire, prompt = null)

        val eol = if (spec.linefeed) "\r\n" else "\r"
        val body = StringBuilder()
        if (spec.echo) body.append(request).append(eol)
        spec.garbageBefore?.let(body::append)
        when (reply) {
            is FakeReply.Lines -> reply.lines.forEach { body.append(it).append(eol) }
            is FakeReply.Fault -> body.append(reply.fault.wire).append(eol)
            else -> Unit
        }

        return WireResponse(body.toString(), if (spec.emitPrompt) "$eol>" else null)
    }
}
