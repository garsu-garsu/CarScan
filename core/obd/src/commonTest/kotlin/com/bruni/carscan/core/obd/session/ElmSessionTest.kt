package com.bruni.carscan.core.obd.session

import com.bruni.carscan.core.obd.ElmErrorKind
import com.bruni.carscan.core.obd.ElmRequest
import com.bruni.carscan.core.obd.ElmResponse
import com.bruni.carscan.core.obd.ElmState
import com.bruni.carscan.core.transport.fake.ElmFault
import com.bruni.carscan.core.transport.fake.FakeObdTransport
import com.bruni.carscan.core.transport.fake.FakeObdTransportBuilder
import com.bruni.carscan.core.transport.fake.FakeReply
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class ElmSessionTest {

    /**
     * A healthy clone: it answers the init ladder, it has no idea what `STI` is, and
     * every AT command it does not otherwise care about comes back `OK`.
     */
    private fun clone(configure: FakeObdTransportBuilder.() -> Unit = {}) = FakeObdTransport {
        fallback = FakeReply.Lines(listOf("OK"))
        on("ATZ") respond "ELM327 v1.5"
        on("ATI") respond "ELM327 v1.5"
        on("STI") fail ElmFault.UNKNOWN_COMMAND
        on("ATDPN") respond "A6"
        on("0100") respond "7E8 06 41 00 88 18 80 01"
        on("01001") respond "7E8 06 41 00 88 18 80 01"
        on("010C") respond "7E8 04 41 0C 1A F8"
        on("010C1") respond "7E8 04 41 0C 1A F8"
        configure()
    }

    private fun TestScope.session(transport: FakeObdTransport, onAssert: (String) -> Unit = {}) =
        ElmSession(transport, backgroundScope, sessionConfig(onAssert))

    // --- the happy path -----------------------------------------------------

    @Test
    fun `an exchange returns the stripped lines and a measured round trip`() = runTest {
        val transport = clone { latency = 40.milliseconds }
        val session = session(transport)
        session.connect()

        val ok = session.exchange(ElmRequest("010C")).shouldBeInstanceOf<ElmResponse.Ok>()

        ok.lines shouldContainExactly listOf("7E804410C1AF8")
        ok.roundTrip shouldBe 40.milliseconds
    }

    /**
     * The transport chunks arbitrarily and a loaded clone dribbles. Framing on the prompt
     * is what makes "one chunk is one line" irrelevant — and it has to be irrelevant,
     * because on BLE it is never true.
     */
    @Test
    fun `a response dribbled out one byte at a time is exactly one Ok`() = runTest {
        val transport = clone {
            chunkSize = 1
            on("010C") respondLines listOf("7E8 10 14 49 02 01 31 47", "7E8 21 31 5A 54 35 33 38")
        }
        val session = session(transport)
        session.connect()

        val ok = session.exchange(ElmRequest("010C")).shouldBeInstanceOf<ElmResponse.Ok>()

        ok.lines shouldContainExactly listOf("7E810144902013147", "7E821315A54353338")
    }

    // --- errors -------------------------------------------------------------

    /**
     * Every way an ELM327 says no, injected by the fake and mapped by the session.
     * `NO DATA` is the one that matters most: strip its space and it is six good hex
     * nibbles, so a framer that parses before it classifies turns a failure into a
     * reading.
     */
    @Test
    fun `every ElmErrorKind is produced and mapped`() = runTest {
        suspend fun kindOf(script: FakeObdTransportBuilder.() -> Unit): ElmErrorKind {
            val transport = clone(script)
            val session = session(transport)
            session.connect()
            val response = session.exchange(ElmRequest("010C", retries = 0))
            return response.shouldBeInstanceOf<ElmResponse.Err>().kind
        }

        kindOf { on("010C") fail ElmFault.NO_DATA } shouldBe ElmErrorKind.NO_DATA
        kindOf { on("010C") fail ElmFault.CAN_ERROR } shouldBe ElmErrorKind.CAN_ERROR
        kindOf { on("010C") fail ElmFault.BUS_BUSY } shouldBe ElmErrorKind.BUS_BUSY
        kindOf { on("010C") fail ElmFault.STOPPED } shouldBe ElmErrorKind.STOPPED
        kindOf { on("010C") fail ElmFault.BUFFER_FULL } shouldBe ElmErrorKind.BUFFER_FULL
        kindOf { on("010C") fail ElmFault.UNKNOWN_COMMAND } shouldBe ElmErrorKind.QUESTION_MARK
        kindOf { on("010C") fail ElmFault.UNABLE_TO_CONNECT } shouldBe ElmErrorKind.UNABLE_TO_CONNECT
        kindOf { on("010C") fail ElmFault.LV_RESET } shouldBe ElmErrorKind.LV_RESET

        // ElmFault has no token for these three, so they go on the wire verbatim.
        kindOf { on("010C") raw "BUS ERROR\r\r>" } shouldBe ElmErrorKind.BUS_ERROR
        kindOf { on("010C") raw "BUS INIT: ERROR\r\r>" } shouldBe ElmErrorKind.BUS_INIT
        kindOf { on("010C") raw "ACT ALERT\r\r>" } shouldBe ElmErrorKind.ACT_ALERT

        // No prompt, ever. The consumer must not hang.
        kindOf { on("010C").silence() } shouldBe ElmErrorKind.TIMEOUT
    }

    /** The car does not support that PID. It is not a fault, and reconnecting will not help. */
    @Test
    fun `NO DATA does not reset the adapter`() = runTest {
        val transport = clone { on("010C") fail ElmFault.NO_DATA }
        val session = session(transport)
        session.connect()
        val base = transport.requests.size

        session.exchange(ElmRequest("010C", retries = 0))

        transport.requests.drop(base) shouldNotContain "ATWS"
        session.state.value shouldBe ElmState.Ready
    }

    /**
     * `BUFFER FULL` means we overran the adapter's input buffer. The frame-count suffix
     * is the first thing to go, because it is the thing that makes the adapter answer
     * faster than it can cope with.
     */
    @Test
    fun `BUFFER FULL retires expectedFrames, shrinks the write chunk, and retries without it`() = runTest {
        val transport = clone { on("010C1") fail ElmFault.BUFFER_FULL }
        val session = session(transport)
        session.connect()
        session.adapterInfo.supportsExpectedFrames shouldBe true
        val chunkBefore = session.adapterInfo.maxWriteChunk
        val base = transport.requests.size

        val response = session.exchange(ElmRequest("010C", expectedFrames = 1))

        response.shouldBeInstanceOf<ElmResponse.Ok>()
        session.adapterInfo.supportsExpectedFrames shouldBe false
        session.adapterInfo.maxWriteChunk shouldBe chunkBefore / 2
        transport.requests.drop(base) shouldContainInOrder listOf("010C1", "010C")
    }

    /** Once the flag is down, the suffix must never go out again — not even once. */
    @Test
    fun `expectedFrames is not sent again after BUFFER FULL`() = runTest {
        val transport = clone { on("010C1") fail ElmFault.BUFFER_FULL }
        val session = session(transport)
        session.connect()
        session.exchange(ElmRequest("010C", expectedFrames = 1))
        val base = transport.requests.size

        session.exchange(ElmRequest("010C", expectedFrames = 1))

        transport.requests.drop(base) shouldContainExactly listOf("010C")
    }

    /** A clone that never learnt the frame-count suffix says `?` to it. Same conclusion. */
    @Test
    fun `a question mark to the frame count retires expectedFrames rather than the command`() = runTest {
        val transport = clone { on("010C1") fail ElmFault.UNKNOWN_COMMAND }
        val session = session(transport)
        session.connect()

        val response = session.exchange(ElmRequest("010C", expectedFrames = 1))

        response.shouldBeInstanceOf<ElmResponse.Ok>()
        session.adapterInfo.supportsExpectedFrames shouldBe false
    }

    /**
     * `?` to an AT command means this adapter does not have it. Asking again every poll
     * costs a round trip every poll, so the fact is cached and the command is dropped.
     */
    @Test
    fun `an AT command the adapter rejects is cached and never sent again`() = runTest {
        val transport = clone { on("ATCRA 7E8") fail ElmFault.UNKNOWN_COMMAND }
        val session = session(transport)
        session.connect()
        val base = transport.requests.size

        val first = session.exchange(ElmRequest("ATCRA 7E8", retries = 0))
        repeat(3) { session.exchange(ElmRequest("ATCRA 7E8", retries = 0)) }

        first.shouldBeInstanceOf<ElmResponse.Err>().kind shouldBe ElmErrorKind.QUESTION_MARK
        transport.requests.drop(base).count { it.startsWith("ATCRA") } shouldBe 1
        session.adapterInfo.supportsRxFilter shouldBe false
    }

    /**
     * `STOPPED` says the adapter received a byte while it was still talking. There is no
     * legitimate path to it: it means something wrote outside the mutex. It should be
     * unreachable, so the only useful response is to find out about it loudly.
     */
    @Test
    fun `STOPPED is asserted on, because it means our own half-duplex bug`() = runTest {
        val complaints = mutableListOf<String>()
        val transport = clone { on("010C") fail ElmFault.STOPPED }
        val session = session(transport) { complaints += it }
        session.connect()

        session.exchange(ElmRequest("010C", retries = 0))

        complaints.size shouldBe 1
        complaints.single().contains("STOPPED") shouldBe true
    }

    /** Transient bus trouble. Worth one retry; a second one means the adapter is wedged. */
    @Test
    fun `CAN ERROR retries once and then warm-starts the adapter`() = runTest {
        val transport = clone {
            on("010C").respondEach(
                FakeReply.Fault(ElmFault.CAN_ERROR),
                FakeReply.Lines(listOf("7E8 04 41 0C 1A F8")),
            )
        }
        val session = session(transport)
        session.connect()
        val base = transport.requests.size

        session.exchange(ElmRequest("010C", retries = 1)).shouldBeInstanceOf<ElmResponse.Ok>()

        transport.requests.drop(base) shouldContainExactly listOf("010C", "010C")
        transport.requests.drop(base) shouldNotContain "ATWS"
    }

    @Test
    fun `CAN ERROR that survives the retry resets the adapter`() = runTest {
        val transport = clone { on("010C") fail ElmFault.CAN_ERROR }
        val session = session(transport)
        session.connect()
        session.atCache.header = "7E0"
        val base = transport.requests.size

        val response = session.exchange(ElmRequest("010C", retries = 1))

        response.shouldBeInstanceOf<ElmResponse.Err>().kind shouldBe ElmErrorKind.CAN_ERROR
        transport.requests.drop(base) shouldContain "ATWS"
        transport.requests.drop(base) shouldContain "ATSH 7E0"
    }

    /**
     * A brown-out on the OBD port. The adapter reset itself, so every AT register we set
     * is gone — and it will answer the next command from the default broadcast header
     * without complaining, which is a wrong reading rather than a missing one.
     */
    @Test
    fun `LV RESET re-initializes and replays the cached AT state`() = runTest {
        val transport = clone { on("010C") fail ElmFault.LV_RESET }
        val session = session(transport)
        session.connect()
        session.atCache.header = "7E4"
        session.atCache.rxFilter = "7EC"
        val base = transport.requests.size

        val response = session.exchange(ElmRequest("010C", retries = 0))

        response.shouldBeInstanceOf<ElmResponse.Err>().kind shouldBe ElmErrorKind.LV_RESET
        val afterwards = transport.requests.drop(base)
        afterwards shouldContain "ATWS"
        afterwards shouldContain "ATH1"
        afterwards shouldContain "ATSH 7E4"
        afterwards shouldContain "ATCRA 7EC"
        session.state.value shouldBe ElmState.Ready
    }

    // --- timeouts and recovery ----------------------------------------------

    @Test
    fun `a timeout drains the adapter and retries once`() = runTest {
        val transport = clone {
            on("010C").respondEach(
                FakeReply.Silence,
                FakeReply.Lines(listOf("7E8 04 41 0C 1A F8")),
            )
        }
        val session = session(transport)
        session.connect()
        val base = transport.requests.size

        val response = session.exchange(ElmRequest("010C", retries = 1))

        response.shouldBeInstanceOf<ElmResponse.Ok>()
        transport.requests.drop(base) shouldContainExactly listOf("010C", "010C")
    }

    /**
     * Two in a row and the adapter is not merely slow — it is wedged. `ATWS` is the way
     * out, and everything `ATWS` throws away has to be put back, or the next command goes
     * out with headers off and the default broadcast header and *still answers*.
     */
    @Test
    fun `two consecutive timeouts warm-start the adapter and replay the cached AT state`() = runTest {
        val transport = clone { on("010C").silence() }
        val session = session(transport)
        session.connect()
        session.atCache.header = "7E4"
        session.atCache.rxFilter = "7EC"
        val base = transport.requests.size

        val states = mutableListOf<ElmState>()
        backgroundScope.launch { session.state.collect { states += it } }
        runCurrent()

        val response = session.exchange(ElmRequest("010C", retries = 1))

        response.shouldBeInstanceOf<ElmResponse.Err>().kind shouldBe ElmErrorKind.TIMEOUT
        val afterwards = transport.requests.drop(base)
        afterwards shouldContain "ATWS"
        afterwards shouldContain "ATH1"
        afterwards shouldContain "ATSH 7E4"
        afterwards shouldContain "ATCRA 7EC"
        states shouldContain ElmState.Recovering
        session.state.value shouldBe ElmState.Ready
    }

    /**
     * A response with nothing outstanding means the prompt stream is one ahead of us.
     * There is no way to reason about anything that arrives after it, so the session
     * never tries: it drains, resets, and re-initializes.
     */
    @Test
    fun `an unsolicited response is a DESYNC and forces a reset`() = runTest {
        val transport = clone()
        val session = session(transport)
        session.connect()
        session.atCache.header = "7E0"
        val base = transport.requests.size

        transport.inject("7E8 04 41 0C 1A F8\r\r>")
        val response = session.exchange(ElmRequest("010C"))

        response.shouldBeInstanceOf<ElmResponse.Err>().kind shouldBe ElmErrorKind.DESYNC
        val afterwards = transport.requests.drop(base)
        afterwards shouldContain "ATWS"
        afterwards shouldContain "ATSH 7E0"
        // The command was never written: we do not send into a stream we cannot trust.
        afterwards shouldNotContain "010C"
        session.state.value shouldBe ElmState.Ready
    }

    @Test
    fun `the session recovers and the next exchange succeeds`() = runTest {
        val transport = clone()
        val session = session(transport)
        session.connect()

        transport.inject("41 0C 1A F8\r\r>")
        session.exchange(ElmRequest("010C"))

        session.exchange(ElmRequest("010C")).shouldBeInstanceOf<ElmResponse.Ok>()
    }

    // --- state --------------------------------------------------------------

    @Test
    fun `state is Disconnected before connect and Ready after`() = runTest {
        val transport = clone()
        val session = session(transport)

        session.state.value shouldBe ElmState.Disconnected
        session.connect()
        session.state.value shouldBe ElmState.Ready

        session.close()
        session.state.value shouldBe ElmState.Disconnected
    }

    @Test
    fun `an adapter that never answers ATZ fails rather than pretending`() = runTest {
        val transport = clone { on("ATZ").silence() }
        val session = session(transport)

        assertFailsWith<ElmInitFailure> { session.connect() }

        session.state.value.shouldBeInstanceOf<ElmState.Failed>()
    }
}
