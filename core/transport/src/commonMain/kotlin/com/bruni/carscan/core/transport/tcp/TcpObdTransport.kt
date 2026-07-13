package com.bruni.carscan.core.transport.tcp

import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.ObdTransport
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.concurrent.Volatile

/**
 * The Wi-Fi adapter: a plain TCP socket, almost always `192.168.0.10:35000`.
 *
 * These are ESP8266-class devices with a few kilobytes of RAM. They drop the
 * connection when the ignition dips, when two apps connect at once, or for no reason
 * at all — so a drop is surfaced as an exception on [incoming] rather than a silent
 * end of stream, and [close] is safe to call from anywhere, twice.
 */
class TcpObdTransport(
    private val host: String,
    private val port: Int,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ObdTransport {

    private var selector: SelectorManager? = null
    private var socket: Socket? = null
    private var reader: ByteReadChannel? = null
    private var writer: ByteWriteChannel? = null

    @Volatile
    private var closed = false

    override suspend fun open() {
        check(!closed) { "TcpObdTransport($host:$port) has already been closed" }
        check(socket == null) { "TcpObdTransport($host:$port) is already open" }

        val manager = SelectorManager(dispatcher)
        val connected = aSocket(manager).tcp().connect(host, port) {
            // Nagle holds a small write back waiting for company. An OBD request is a
            // handful of bytes and the answer cannot begin until it lands, so leaving
            // Nagle on adds a delay to every single poll and wrecks the poll rate.
            noDelay = true
            keepAlive = true
        }

        selector = manager
        socket = connected
        reader = connected.openReadChannel()
        writer = connected.openWriteChannel(autoFlush = true)
    }

    /**
     * Reads straight off the socket, so the chunking is whatever TCP hands us: half a
     * line, three lines and a fragment, one byte. Nothing here pretends otherwise.
     */
    override val incoming: Flow<ByteArray> = flow {
        val channel = reader
            ?: error("TcpObdTransport($host:$port): incoming was collected before open()")
        val buffer = ByteArray(READ_BUFFER_BYTES)

        while (true) {
            val read: Int
            try {
                read = channel.readAvailable(buffer, 0, buffer.size)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // A drop we asked for is just the end. A drop we did not is a fact the
                // session needs, and swallowing it here is how a reconnect never happens.
                if (closed) return@flow
                throw failure
            }
            if (read < 0) return@flow
            if (read > 0) emit(buffer.copyOf(read))
        }
    }

    override suspend fun write(bytes: ByteArray) {
        val channel = writer
        if (channel == null || closed) {
            error("TcpObdTransport($host:$port): write() on a transport that is not open")
        }
        channel.writeFully(bytes)
        channel.flush()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        runCatching { socket?.close() }
        runCatching { selector?.close() }
        socket = null
        reader = null
        writer = null
    }

    companion object {
        private const val READ_BUFFER_BYTES = 4096

        /** `"192.168.0.10:35000"` — the address format [DiscoveredAdapter] carries for Wi-Fi. */
        fun of(adapter: DiscoveredAdapter): TcpObdTransport {
            val host = adapter.address.substringBeforeLast(':')
            val port = adapter.address.substringAfterLast(':').toIntOrNull()
                ?: error("Not a host:port address: ${adapter.address}")
            return TcpObdTransport(host, port)
        }
    }
}
