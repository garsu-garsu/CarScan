package com.bruni.carscan.core.obd.session

import com.bruni.carscan.core.obd.ElmRequest
import com.bruni.carscan.core.obd.ElmResponse
import com.bruni.carscan.core.transport.fake.ElmEmulator
import com.bruni.carscan.core.transport.fake.ElmEmulatorConfig
import com.bruni.carscan.core.transport.fake.ElmFault
import com.bruni.carscan.core.transport.fake.FakeObdTransport
import com.bruni.carscan.core.transport.fake.FakeObdTransportBuilder
import com.bruni.carscan.core.transport.fake.FakeReply
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class ElmInitializerTest {

    private fun clone(configure: FakeObdTransportBuilder.() -> Unit = {}) = FakeObdTransport {
        fallback = FakeReply.Lines(listOf("OK"))
        on("ATZ") respond "ELM327 v1.5"
        on("ATI") respond "ELM327 v1.5"
        on("STI") fail ElmFault.UNKNOWN_COMMAND
        on("ATDPN") respond "A6"
        on("0100") respond "7E8 06 41 00 88 18 80 01"
        on("01001") respond "7E8 06 41 00 88 18 80 01"
        configure()
    }

    // --- the ladder, against something that behaves like an adapter ---------

    /**
     * The emulator holds real AT state, so this asserts what the adapter *is* afterwards
     * rather than what we hoped we told it. `ATH1` in particular is the opposite of what
     * most OBD code does and it is deliberate: without CAN ids on every line, two ECUs
     * answering the same broadcast are indistinguishable.
     */
    @Test
    fun `the ladder leaves the adapter in the state the decoder needs`() = runTest {
        val emulator = ElmEmulator(clock = elmClock())
        val session = ElmSession(emulator, backgroundScope, sessionConfig())

        session.connect()

        emulator.atState.echo shouldBe false        // ATE0
        emulator.atState.linefeed shouldBe false    // ATL0
        emulator.atState.spaces shouldBe false      // ATS0 — ~30% of the bytes on the wire
        emulator.atState.headers shouldBe true      // ATH1 — we demux the ECUs ourselves
        emulator.atState.canAutoFormat shouldBe true
        emulator.atState.adaptiveTiming shouldBe 1
        emulator.atState.protocol shouldBe 6
    }

    @Test
    fun `ATE0 really does suppress the echo`() = runTest {
        val emulator = ElmEmulator(clock = elmClock())
        val session = ElmSession(emulator, backgroundScope, sessionConfig())

        val info = session.connect()

        info.echoSuppressionNeeded shouldBe false
        val ok = session.exchange(ElmRequest("010C")).shouldBeInstanceOf<ElmResponse.Ok>()
        ok.lines.none { it.startsWith("010C") } shouldBe true
    }

    /**
     * And the clone that ignores it is still handled — we strip the echo ourselves. The
     * detection cannot be done on `ATE0`'s own reply: an ELM327 mirrors characters as
     * they arrive, so `ATE0` always echoes its own name whatever it then does with the
     * setting. It is the *next* command that tells you.
     */
    @Test
    fun `a clone that keeps echoing after ATE0 is detected and still works`() = runTest {
        val transport = clone {
            echo = true
            on("010C") respond "7E8 04 41 0C 1A F8"
        }
        val session = ElmSession(transport, backgroundScope, sessionConfig())

        val info = session.connect()

        info.echoSuppressionNeeded shouldBe true

        val ok = session.exchange(ElmRequest("010C")).shouldBeInstanceOf<ElmResponse.Ok>()
        ok.lines shouldNotContain "010C"
        ok.lines shouldContain "7E804410C1AF8"
    }

    @Test
    fun `the ladder is sent in order`() = runTest {
        val transport = clone()
        val session = ElmSession(transport, backgroundScope, sessionConfig())

        session.connect()

        // The last 0100 goes out as `01001` — the frame-count suffix rides along so that an
        // adapter which cannot do it says so here, not on the first gauge the user opens.
        transport.requests shouldContainInOrder listOf(
            "ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATCAF1",
            "ATSP0", "0100", "ATDPN", "ATAT1", "01001",
        )
    }

    // --- feature detection, because ATI is a lie ----------------------------

    /** Every clone answers `ELM327 v2.1` and then rejects half of what a v2.1 does. */
    @Test
    fun `a clone claiming v2_1 is not believed`() = runTest {
        val transport = clone {
            on("ATI") respond "ELM327 v2.1"
            on("STI") fail ElmFault.UNKNOWN_COMMAND
            on("ATCRA") fail ElmFault.UNKNOWN_COMMAND
            on("01001") fail ElmFault.UNKNOWN_COMMAND
        }
        val session = ElmSession(transport, backgroundScope, sessionConfig())

        val info = session.connect()

        info.identity shouldBe "ELM327V2.1"
        info.isStn shouldBe false
        info.supportsRxFilter shouldBe false
        info.supportsExpectedFrames shouldBe false
    }

    /** Only a genuine STN answers `STI`. It is the one honest question you can ask. */
    @Test
    fun `an adapter that answers STI is an STN`() = runTest {
        val transport = clone { on("STI") respond "STN1170 v4.6.1" }
        val session = ElmSession(transport, backgroundScope, sessionConfig())

        val info = session.connect()

        info.isStn shouldBe true
    }

    @Test
    fun `the round trip is measured, not guessed`() = runTest {
        val transport = clone { latency = 35.milliseconds }
        val session = ElmSession(transport, backgroundScope, sessionConfig())

        val info = session.connect()

        info.rttMs shouldBe 35
    }

    // --- protocol -----------------------------------------------------------

    /**
     * `ATSP0` on its own detects nothing: the adapter only searches when it has something
     * to send. A real `0100` is what forces the search, and `ATDPN` reads back what it
     * settled on.
     */
    @Test
    fun `an unknown protocol is searched for with a real 0100 and read back`() = runTest {
        val transport = clone()
        val session = ElmSession(transport, backgroundScope, sessionConfig())

        val info = session.connect()

        transport.requests shouldContainInOrder listOf("ATSP0", "0100", "ATDPN")
        info.protocolNum shouldBe 6
        session.atCache.protocol shouldBe 6
    }

    /** The search costs seconds on a cold bus, and the answer never changes for a car. */
    @Test
    fun `a known protocol is set directly and the search is skipped`() = runTest {
        val transport = clone()
        val session = ElmSession(transport, backgroundScope, sessionConfig())
        session.atCache.protocol = 6

        val info = session.connect()

        transport.requests shouldContain "ATSP6"
        transport.requests shouldNotContain "ATSP0"
        transport.requests shouldNotContain "ATDPN"
        info.protocolNum shouldBe 6
    }

    // --- throughput ---------------------------------------------------------

    /**
     * The whole ladder has to be cheap: it runs on every connect, and the user is looking
     * at a spinner while it does. This is a regression guard, not a target.
     */
    @Test
    fun `the ladder costs a bounded number of round trips`() = runTest {
        val transport = clone()
        val session = ElmSession(transport, backgroundScope, sessionConfig())

        session.connect()

        transport.requests.size shouldBeGreaterThan 8
        (transport.requests.size <= 16) shouldBe true
    }

    @Test
    fun `the emulator throughput ceiling is respected without deadlocking`() = runTest {
        val emulator = ElmEmulator(
            clock = elmClock(),
            config = ElmEmulatorConfig(maxExchangesPerSecond = 15),
        )
        val session = ElmSession(emulator, backgroundScope, sessionConfig())
        session.connect()

        repeat(10) {
            session.exchange(ElmRequest("010C")).shouldBeInstanceOf<ElmResponse.Ok>()
        }
    }
}
