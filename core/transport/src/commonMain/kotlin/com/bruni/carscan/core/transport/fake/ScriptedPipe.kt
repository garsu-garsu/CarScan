package com.bruni.carscan.core.transport.fake

import com.bruni.carscan.core.transport.ObdTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

/**
 * The characters an adapter sends back, split at the prompt so the prompt can lag —
 * which is exactly what a loaded clone does, and exactly what breaks a consumer that
 * assumes the prompt arrives with the payload.
 */
internal class WireResponse(val body: String, val prompt: String?)

/** Turns one command into its wire response. `null` means the adapter says nothing at all. */
internal fun interface Responder {
    suspend fun respond(request: String): WireResponse?
}

/**
 * The byte plumbing shared by [FakeObdTransport] and [ElmEmulator].
 *
 * It does three things a real adapter does and a naive fake does not:
 * accumulate written bytes until a CR (the consumer is free to write one byte at a
 * time), answer strictly one command at a time (an ELM327 is half duplex; overlapping
 * answers are a bug the fake must not paper over), and hand the answer back in
 * whatever chunk sizes it was told to — down to one byte each.
 *
 * Bytes go into an unbounded channel rather than a SharedFlow, so nothing is lost if
 * the consumer subscribes late. A real socket buffers too.
 */
internal class ScriptedPipe(
    private val responder: Responder,
    private val chunkSize: Int?,
    private val promptDelay: Duration,
) : ObdTransport {

    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    private val halfDuplex = Mutex()
    private val partial = StringBuilder()
    private val seen = mutableListOf<String>()

    private var scope: CoroutineScope? = null
    private var closed = false

    /** Every command the consumer has sent, in order. */
    val requests: List<String> get() = seen.toList()

    /**
     * Single consumer, like the socket it stands in for: two collectors would race for
     * the same bytes and each would see half a response. ElmSession is the one consumer.
     */
    override val incoming: Flow<ByteArray> = outgoing.receiveAsFlow()

    override suspend fun open() {
        check(!closed) { "This transport has already been closed" }
        // Inheriting the caller's context is what puts the response delays on runTest's
        // virtual clock instead of a real one.
        scope = CoroutineScope(coroutineContext + SupervisorJob())
    }

    override suspend fun write(bytes: ByteArray) {
        val running = scope
        if (running == null || closed) error("write() on a transport that is not open")

        for (byte in bytes) {
            val char = byte.toInt().toChar()
            if (char != '\r' && char != '\n') {
                partial.append(char)
                continue
            }
            val request = partial.toString().trim()
            partial.clear()
            if (request.isNotEmpty()) {
                seen += request
                running.launch { answer(request) }
            }
        }
    }

    private suspend fun answer(request: String) {
        halfDuplex.withLock {
            val response = responder.respond(request) ?: return
            emit(response.body)
            response.prompt?.let { prompt ->
                if (promptDelay > Duration.ZERO) delay(promptDelay)
                emit(prompt)
            }
        }
    }

    /** Bytes the consumer never asked for: noise, a late answer, a reset banner. */
    suspend fun inject(raw: String) = emit(raw)

    private suspend fun emit(text: String) {
        if (text.isEmpty()) return
        val bytes = text.encodeToByteArray()
        val size = chunkSize
        if (size == null || size >= bytes.size) {
            outgoing.send(bytes)
            return
        }
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + size, bytes.size)
            outgoing.send(bytes.copyOfRange(offset, end))
            offset = end
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        scope?.cancel()
        scope = null
        outgoing.close()
    }
}
