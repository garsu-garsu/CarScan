package com.bruni.carscan.core.transport.tcp

import com.bruni.carscan.core.transport.TestPromptFramer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class TcpObdTransportTest {

    @Test
    fun `close before open is a no-op, and close is idempotent`() = runTest {
        val transport = TcpObdTransport("192.0.2.1", 35000)
        transport.close()
        transport.close()
    }

    @Test
    fun `writing before open fails loudly rather than hanging`() = runTest {
        val transport = TcpObdTransport("192.0.2.1", 35000)
        val error = runCatching { transport.write("ATI\r".encodeToByteArray()) }.exceptionOrNull()
        (error is IllegalStateException) shouldBe true
    }

    /**
     * A real loopback socket. This is the only place in the module that touches IO, and
     * it exists to prove the two things a pure test cannot: that the ktor connect path
     * (with TCP_NODELAY) actually works, and that bytes arrive as arbitrary chunks.
     */
    @Test
    fun `it connects, writes, reads back arbitrary chunks, and fails loudly after close`() =
        runTest(timeout = 30.seconds) {
            withContext(Dispatchers.Default) {
                val selector = SelectorManager(Dispatchers.IO)
                val server = aSocket(selector).tcp().bind(InetSocketAddress("127.0.0.1", 0))
                val port = (server.localAddress as InetSocketAddress).port
                val received = CompletableDeferred<String>()

                val serverJob = launch {
                    val peer = server.accept()
                    val read = peer.openReadChannel()
                    val write = peer.openWriteChannel(autoFlush = true)
                    val buffer = ByteArray(64)
                    val n = read.readAvailable(buffer, 0, buffer.size)
                    received.complete(buffer.decodeToString(0, n))
                    // Deliberately split across two writes: the transport must not
                    // pretend one read is one line.
                    write.writeFully("41 0C 1A".encodeToByteArray())
                    write.flush()
                    write.writeFully(" F8\r\r>".encodeToByteArray())
                    write.flush()
                }

                val transport = TcpObdTransport("127.0.0.1", port)
                transport.open()

                val framer = TestPromptFramer()
                val collector = launch {
                    transport.incoming.collect { framer.feed(it) }
                }

                transport.write("010C\r".encodeToByteArray())
                received.await() shouldBe "010C\r"

                while (framer.responses.isEmpty()) kotlinx.coroutines.yield()

                framer.responses.single()
                    .split('\r')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() } shouldContainExactly listOf("41 0C 1A F8")

                transport.close()
                transport.close()

                val error = runCatching { transport.write("ATI\r".encodeToByteArray()) }
                    .exceptionOrNull()
                (error is IllegalStateException) shouldBe true

                collector.cancel()
                serverJob.cancel()
                server.close()
                selector.close()
            }
        }
}
