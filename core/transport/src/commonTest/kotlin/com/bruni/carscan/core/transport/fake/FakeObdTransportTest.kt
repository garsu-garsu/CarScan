package com.bruni.carscan.core.transport.fake

import app.cash.turbine.test
import com.bruni.carscan.core.transport.TestElmClient
import com.bruni.carscan.core.transport.TestPromptFramer
import com.bruni.carscan.core.transport.elmLines
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class FakeObdTransportTest {

    /**
     * The single most valuable test in the project. Framing code that assumes
     * "one chunk == one line" passes against a good adapter and fails in the field,
     * so the fake must be able to reproduce the worst case exactly: one byte at a time.
     *
     * The wire here is 37 bytes:
     *   "220101\r"                        =  7
     *   "7EC 10 09 62 01 01 EF FB E7\r"   = 28
     *   "\r>"                             =  2
     */
    @Test
    fun `a response delivered one byte at a time still frames as exactly one response`() = runTest {
        val transport = FakeObdTransport {
            echo = true
            chunkSize = 1
            on("220101") respond "7EC 10 09 62 01 01 EF FB E7"
        }
        transport.open()

        val framer = TestPromptFramer()
        val chunks = mutableListOf<ByteArray>()

        transport.incoming.test {
            transport.write("220101\r".encodeToByteArray())
            while (framer.responses.isEmpty()) {
                val chunk = awaitItem()
                chunks += chunk
                framer.feed(chunk)
            }
            cancelAndIgnoreRemainingEvents()
        }

        chunks.size shouldBe 37
        chunks.all { it.size == 1 } shouldBe true
        framer.responses.size shouldBe 1
        framer.responses.single().elmLines() shouldContainExactly
            listOf("220101", "7EC 10 09 62 01 01 EF FB E7")
    }

    @Test
    fun `an adapter that ignores ATE0 keeps echoing the request`() = runTest {
        val transport = FakeObdTransport {
            echo = true
            on("ATE0") respond "OK"
        }
        transport.open()
        val client = TestElmClient(transport, backgroundScope)

        client.exchangeLines("ATE0") shouldContainExactly listOf("ATE0", "OK")
    }

    @Test
    fun `BUFFER FULL is emitted and observable`() = runTest {
        val transport = FakeObdTransport {
            on("0100") fail ElmFault.BUFFER_FULL
        }
        transport.open()
        val client = TestElmClient(transport, backgroundScope)

        client.exchangeLines("0100") shouldContainExactly listOf("BUFFER FULL")
    }

    @Test
    fun `a rule walks its replies in order and repeats the last one`() = runTest {
        val transport = FakeObdTransport {
            on("010C") fail ElmFault.CAN_ERROR respond "7E8 04 41 0C 1A F8"
        }
        transport.open()
        val client = TestElmClient(transport, backgroundScope)

        client.exchangeLines("010C") shouldContainExactly listOf("CAN ERROR")
        client.exchangeLines("010C") shouldContainExactly listOf("7E8 04 41 0C 1A F8")
        client.exchangeLines("010C") shouldContainExactly listOf("7E8 04 41 0C 1A F8")
    }

    @Test
    fun `latency is spent on virtual time, not wall time`() = runTest {
        val transport = FakeObdTransport {
            latency = 20.milliseconds
            on("010C") respond "7E8 04 41 0C 1A F8"
        }
        transport.open()
        val client = TestElmClient(transport, backgroundScope)

        val before = testScheduler.currentTime
        client.exchange("010C")
        testScheduler.currentTime - before shouldBe 20L
    }

    @Test
    fun `a silent adapter never answers`() = runTest {
        val transport = FakeObdTransport {
            on("010C").silence()
        }
        transport.open()
        val client = TestElmClient(transport, backgroundScope)

        withTimeoutOrNull(5.seconds) { client.exchange("010C") }.shouldBeNull()
    }

    @Test
    fun `an unmatched request falls back to NO DATA`() = runTest {
        val transport = FakeObdTransport {}
        transport.open()
        val client = TestElmClient(transport, backgroundScope)

        client.exchangeLines("0199") shouldContainExactly listOf("NO DATA")
    }

    @Test
    fun `bytes are accumulated until CR, so a command may be written one byte at a time`() = runTest {
        val transport = FakeObdTransport {
            on("010D") respond "7E8 03 41 0D 3C"
        }
        transport.open()

        transport.incoming.test {
            for (c in "010D\r") transport.write(byteArrayOf(c.code.toByte()))
            val framer = TestPromptFramer()
            while (framer.responses.isEmpty()) framer.feed(awaitItem())
            framer.responses.single().elmLines() shouldContainExactly listOf("7E8 03 41 0D 3C")
            cancelAndIgnoreRemainingEvents()
        }
        transport.requests shouldContainExactly listOf("010D")
    }

    @Test
    fun `garbage can be interleaved ahead of the response to exercise desync detection`() = runTest {
        val transport = FakeObdTransport {
            garbageBefore = "STOPPED\r"
            on("010C") respond "7E8 04 41 0C 1A F8"
        }
        transport.open()
        val client = TestElmClient(transport, backgroundScope)

        client.exchangeLines("010C") shouldContainExactly listOf("STOPPED", "7E8 04 41 0C 1A F8")
    }

    @Test
    fun `a delayed prompt does not arrive with the payload`() = runTest {
        val transport = FakeObdTransport {
            promptDelay = 500.milliseconds
            on("010C") respond "7E8 04 41 0C 1A F8"
        }
        transport.open()

        transport.incoming.test {
            val start = testScheduler.currentTime
            transport.write("010C\r".encodeToByteArray())
            val payload = awaitItem().decodeToString()
            payload shouldBe "7E8 04 41 0C 1A F8\r"
            val prompt = awaitItem().decodeToString()
            prompt shouldBe "\r>"
            (testScheduler.currentTime - start) shouldBe 500L
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `close is idempotent and completes the incoming flow`() = runTest {
        val transport = FakeObdTransport {}
        transport.open()

        transport.incoming.test {
            transport.close()
            transport.close()
            awaitComplete()
        }
    }

    @Test
    fun `writing after close fails loudly`() = runTest {
        val transport = FakeObdTransport {}
        transport.open()
        transport.close()

        val error = runCatching { transport.write("010C\r".encodeToByteArray()) }.exceptionOrNull()
        (error is IllegalStateException) shouldBe true
    }
}
