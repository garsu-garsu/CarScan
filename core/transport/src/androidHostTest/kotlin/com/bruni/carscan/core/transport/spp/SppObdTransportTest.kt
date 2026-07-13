package com.bruni.carscan.core.transport.spp

import app.cash.turbine.test
import io.kotest.matchers.shouldBe
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
// Real threads, not virtual time: the pump blocks in InputStream.read, and the whole point
// of these tests is that only closing the socket ends that block. runTest's virtual clock
// would skip ahead of the IO thread and time Turbine out spuriously.
import kotlinx.coroutines.runBlocking

private const val ADDRESS = "00:1D:A5:68:98:8B"

class SppObdTransportTest {

    @Test
    fun `writing before open fails with a clear error`() = runBlocking<Unit> {
        val transport = SppObdTransport(ADDRESS, FakeBluetoothHost())

        assertFailsWith<SppNotOpenException> { transport.write("ATZ\r".toByteArray()) }
    }

    @Test
    fun `collecting before open fails with a clear error`() = runBlocking<Unit> {
        val transport = SppObdTransport(ADDRESS, FakeBluetoothHost())

        assertFailsWith<SppNotOpenException> { transport.incoming.first() }
    }

    @Test
    fun `open with Bluetooth turned off says so, instead of walking the ladder`() = runBlocking<Unit> {
        val host = FakeBluetoothHost(enabled = false)
        val transport = SppObdTransport(ADDRESS, host)

        assertFailsWith<SppBluetoothOffException> { transport.open() }
        host.log.isEmpty() shouldBe true
    }

    @Test
    fun `open connects through the ladder`() = runBlocking<Unit> {
        val host = FakeBluetoothHost()
        val transport = SppObdTransport(ADDRESS, host)

        transport.open()

        host.factory.created.single().connected shouldBe true
    }

    @Test
    fun `incoming emits chunks exactly as they arrive, without looking for line boundaries`() = runBlocking<Unit> {
        // Framing on '>' belongs to :core:obd. The transport must not merge, split or
        // reorder what the radio handed it — a chunk holding half a line stays half a line.
        val host = FakeBluetoothHost()
        val transport = SppObdTransport(ADDRESS, host)
        transport.open()
        val socket = host.factory.created.single()

        transport.incoming.test {
            socket.stream.feed("41 0C 1A".toByteArray())
            socket.stream.feed("F8\r\r".toByteArray())
            socket.stream.feed(">".toByteArray())

            awaitItem().decodeToString() shouldBe "41 0C 1A"
            awaitItem().decodeToString() shouldBe "F8\r\r"
            awaitItem().decodeToString() shouldBe ">"

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `write hands the bytes to the socket`() = runBlocking<Unit> {
        val host = FakeBluetoothHost()
        val transport = SppObdTransport(ADDRESS, host)
        transport.open()

        transport.write("ATZ\r".toByteArray())
        transport.write("0100\r".toByteArray())

        host.factory.created.single().written.toByteArray().decodeToString() shouldBe "ATZ\r0100\r"
    }

    @Test
    fun `close is idempotent`() = runBlocking<Unit> {
        val host = FakeBluetoothHost()
        val transport = SppObdTransport(ADDRESS, host)
        transport.open()

        transport.close()
        transport.close()
        transport.close()

        host.factory.created.single().closeCount shouldBe 1
    }

    @Test
    fun `close terminates incoming`() = runBlocking<Unit> {
        val host = FakeBluetoothHost()
        val transport = SppObdTransport(ADDRESS, host)
        transport.open()
        val socket = host.factory.created.single()

        transport.incoming.test {
            socket.stream.feed(">".toByteArray())
            awaitItem().decodeToString() shouldBe ">"

            transport.close()

            awaitComplete()
        }
    }

    @Test
    fun `cancelling collection closes the socket`() = runBlocking<Unit> {
        // A blocking RFCOMM read ignores thread interruption. If cancellation does not
        // close the socket, the read thread is stranded for the lifetime of the process.
        val host = FakeBluetoothHost()
        val transport = SppObdTransport(ADDRESS, host)
        transport.open()
        val socket = host.factory.created.single()

        val collecting = launch(start = CoroutineStart.UNDISPATCHED) {
            transport.incoming.collect { }
        }
        collecting.cancelAndJoin()

        socket.closeCount shouldBe 1
    }

    @Test
    fun `a second concurrent collector is rejected instead of silently stealing bytes`() = runBlocking<Unit> {
        val host = FakeBluetoothHost()
        val transport = SppObdTransport(ADDRESS, host)
        transport.open()
        val socket = host.factory.created.single()

        transport.incoming.test {
            socket.stream.feed(">".toByteArray())
            awaitItem().decodeToString() shouldBe ">"

            // Two pumps on one InputStream split the byte stream between them: each gets part
            // of every response, and ElmSession desyncs permanently on a bug that reproduces
            // only under load. Fail loudly instead.
            assertFailsWith<IllegalStateException> { transport.incoming.first() }

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an adapter that hangs up ends incoming`() = runBlocking<Unit> {
        val host = FakeBluetoothHost()
        val transport = SppObdTransport(ADDRESS, host)
        transport.open()
        val socket = host.factory.created.single()

        transport.incoming.test {
            socket.stream.feedEof()

            awaitComplete()
        }
    }

    @Test
    fun `a dropped link surfaces as SppLinkLostException, not a bare IOException`() = runBlocking<Unit> {
        val host = FakeBluetoothHost()
        val transport = SppObdTransport(ADDRESS, host)
        transport.open()
        val socket = host.factory.created.single()

        transport.incoming.test {
            socket.stream.feedFailure(IOException("bt socket closed, read return: -1"))

            val error = awaitError()
            (error is SppLinkLostException) shouldBe true
            error.message.orEmpty().contains(ADDRESS) shouldBe true
        }
    }
}
