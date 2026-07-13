package com.bruni.carscan.core.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A deliberately naive stand-in for the real PromptFramer that will live in :core:obd.
 *
 * It exists so the transport tests can assert the property that actually matters —
 * "however the bytes are chunked, a consumer framing on '>' sees exactly one response" —
 * without depending on a module this one must not depend on.
 */
class TestPromptFramer {
    private val pending = StringBuilder()
    private val done = mutableListOf<String>()

    /** Returns the responses completed by this chunk (usually zero or one). */
    fun feed(chunk: ByteArray): List<String> {
        val completed = mutableListOf<String>()
        for (byte in chunk) {
            val c = byte.toInt().toChar()
            if (c == '>') {
                completed += pending.toString()
                pending.clear()
            } else {
                pending.append(c)
            }
        }
        done += completed
        return completed
    }

    val responses: List<String> get() = done.toList()
}

/** Splits a framed response into its non-empty lines, the way ElmSession will. */
fun String.elmLines(): List<String> =
    split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Drives an [ObdTransport] the way ElmSession will: write a command, wait for the '>'
 * prompt, hand back everything that arrived in between. Strictly half-duplex.
 */
class TestElmClient(private val transport: ObdTransport, scope: CoroutineScope) {
    private val framer = TestPromptFramer()
    private val responses = Channel<String>(Channel.UNLIMITED)

    init {
        scope.launch {
            transport.incoming.collect { chunk ->
                framer.feed(chunk).forEach { responses.send(it) }
            }
        }
    }

    suspend fun exchange(command: String): String {
        transport.write("$command\r".encodeToByteArray())
        return responses.receive()
    }

    /** The lines of the response, echo included if the adapter echoes. */
    suspend fun exchangeLines(command: String): List<String> = exchange(command).elmLines()
}
