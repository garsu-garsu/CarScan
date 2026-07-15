package com.bruni.carscan.obd

import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.ConnectOutcome
import com.bruni.carscan.core.obd.ElmErrorKind
import com.bruni.carscan.core.transport.fake.ElmClock
import com.bruni.carscan.core.transport.fake.ElmEmulator
import com.bruni.carscan.core.transport.fake.ElmEmulatorConfig
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ElmObdConnectorTest {

    /**
     * **The only evidence the quirks table does anything at all.**
     *
     * `protocol_num` is persisted so the next session can skip the protocol search — and the
     * search is not a round trip, it is *seconds*. `ATSP0` detects nothing on its own, so the
     * initializer has to follow it with a real `0100` and wait out the search timeout on a cold
     * bus. Seeding `AtStateCache.protocol` from the remembered quirks replaces both with one
     * `ATSP6`.
     *
     * Asserting on `AdapterInfo.protocolNum` would not test this: a session that searched all
     * over again arrives at exactly the same number. The claim is about what went on the wire, so
     * the wire is what is asserted on.
     */
    @Test
    fun `the second connect sends ATSP6 and never searches with ATSP0`() = runTest {
        val transports = FakeTransports {
            ElmEmulator(clock = ElmClock { testScheduler.currentTime })
        }
        val connector = connector(transports)

        val first = connector.connect(BLE_TARGET).shouldBeInstanceOf<ConnectOutcome.Ready>()
        connector.disconnect()

        first.adapter.protocolNum shouldBe 6
        first.adapter.quirks.protocolNum shouldBe 6L
        // The first connect has nothing to go on, so it must search. If it did not, the second
        // connect would prove nothing.
        transports.opened[0].commands shouldContain "ATSP0"

        val second = connector.connect(BLE_TARGET, remembered = first.adapter.quirks)
            .shouldBeInstanceOf<ConnectOutcome.Ready>()
        connector.disconnect()

        transports.opened[1].commands shouldContain "ATSP6"
        transports.opened[1].commands shouldNotContain "ATSP0"
        second.adapter.protocolNum shouldBe 6
    }

    /** And the remembered quirks are handed to the transport, so BLE can reuse the GATT layout. */
    @Test
    fun `the remembered quirks reach the transport`() = runTest {
        val transports = FakeTransports {
            ElmEmulator(clock = ElmClock { testScheduler.currentTime })
        }
        val connector = connector(transports)

        val first = connector.connect(BLE_TARGET).shouldBeInstanceOf<ConnectOutcome.Ready>()
        connector.disconnect()
        transports.lastRemembered shouldBe null

        connector.connect(BLE_TARGET, remembered = first.adapter.quirks)
        connector.disconnect()

        transports.lastRemembered shouldBe first.adapter.quirks
    }

    /**
     * `BUFFER FULL` means we overran the adapter's input buffer. `ElmSession` halves the write
     * chunk, retires the frame-count suffix and carries on — the session *works*. Showing a user
     * an ELM error code for a connection that succeeded is worse than showing them nothing, so it
     * must never reach [ConnectFailure].
     */
    @Test
    fun `an adapter that rejects the frame-count suffix still connects`() = runTest {
        val transports = FakeTransports {
            ElmEmulator(
                clock = ElmClock { testScheduler.currentTime },
                config = ElmEmulatorConfig(supportsExpectedFrames = false),
            )
        }
        val connector = connector(transports)

        connector.connect(BLE_TARGET).shouldBeInstanceOf<ConnectOutcome.Ready>()
        connector.disconnect()
    }

    /**
     * `UNABLE TO CONNECT` during init is the ignition being off, far more often than not — and
     * "turn the ignition on" is a fix, where an ELM error code is not.
     */
    @Test
    fun `UNABLE TO CONNECT during init becomes IGNITION_OFF`() {
        ElmErrorKind.UNABLE_TO_CONNECT.asConnectFailure() shouldBe ConnectFailure.IGNITION_OFF
    }

    /**
     * Everything else the initializer can die of is the adapter not answering. Note what is NOT
     * reachable here: `BUFFER_FULL` and `QUESTION_MARK` — the session degrades through both and
     * connects anyway, so they never become an [ElmInitFailure] at all.
     */
    @Test
    fun `every other init failure becomes ADAPTER_UNREACHABLE`() {
        ElmErrorKind.TIMEOUT.asConnectFailure() shouldBe ConnectFailure.ADAPTER_UNREACHABLE
        ElmErrorKind.CAN_ERROR.asConnectFailure() shouldBe ConnectFailure.ADAPTER_UNREACHABLE
        ElmErrorKind.DESYNC.asConnectFailure() shouldBe ConnectFailure.ADAPTER_UNREACHABLE
        // The adapter simply never answered: no error response, so no kind.
        null.asConnectFailure() shouldBe ConnectFailure.ADAPTER_UNREACHABLE
    }
}

/** The connector under test, with no signalset — these tests are about the wire, not the car. */
internal fun TestScope.connector(transports: Transports) = ElmObdConnector(
    transports = transports,
    signalsets = signalsetOf(null),
    scope = backgroundScope,
)
