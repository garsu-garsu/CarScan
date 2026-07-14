package com.bruni.carscan.feature.connect

import app.cash.turbine.test
import com.bruni.carscan.core.data.ConnectFailure
import com.bruni.carscan.core.data.ConnectOutcome
import com.bruni.carscan.core.data.ConnectionState
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class ConnectViewModelTest {

    // viewModelScope is Dispatchers.Main.immediate, which does not exist in a JVM unit test.
    // Sharing one scheduler with runTest is what lets the ViewModel's own launches advance
    // under virtual time instead of hanging.
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        connector: FakeObdConnector = FakeObdConnector.readyWith(summary()),
        adapters: FakeAdapterRepository = FakeAdapterRepository(),
        session: FakeSessionRepository = FakeSessionRepository(),
    ) = ConnectViewModel(connector, adapters, session, nowMs = { 1_000L })

    // --- the happy path, as a sequence -------------------------------------------------

    @Test
    fun `scan, list, select, connect — Ready carries what the adapter turned out to be`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.state.test {
            awaitItem().let { initial ->
                initial.isScanning shouldBe false
                initial.hasAdapters shouldBe false
            }

            vm.onIntent(ConnectIntent.Scan)
            awaitItem().isScanning shouldBe true

            // The fake trickles its adapters in one at a time, the way a real scan does.
            // Drain until the scan reports itself finished.
            var listed = awaitItem()
            while (listed.isScanning) listed = awaitItem()

            val ble = listed.sections.single { it.kind == TransportKind.BLE }.adapters
            ble.map { it.address } shouldContainExactly listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02")

            vm.onIntent(ConnectIntent.Select(ble.first()))
            awaitItem().connectingTo shouldBe ble.first()

            val ready = awaitItem()
            ready.connectingTo.shouldBeNull()
            ready.failure.shouldBeNull()
            ready.ready.shouldNotBeNull().let { readout ->
                readout.adapter shouldBe ble.first()
                readout.isStn shouldBe false
                // ATDPN said 6, so this is what was actually negotiated — not what we hoped for.
                readout.protocol shouldBe "ISO 15765-4 CAN (11 bit, 500 kbaud)"
                readout.reusedProfile shouldBe false
            }
        }
    }

    // --- the capability rule ------------------------------------------------------------

    @Test
    fun `iOS has no Bluetooth Classic section — the picker branches on capability, not platform`() =
        runTest(dispatcher) {
            // This is what iOS looks like: Apple's ExternalAccessory framework reaches only MFi
            // hardware and no ELM327 clone is MFi, so SPP is permanently impossible there.
            //
            // The test runs on the JVM. An implementation that wrote `if (isAndroid) addSpp()`
            // would pass every other test in this file and fail this one — which is the point.
            val ios = FakeObdConnector.readyWith(
                summary(),
                supported = setOf(TransportKind.BLE, TransportKind.WIFI),
            )

            viewModel(connector = ios).state.value.sections.map { it.kind } shouldContainExactly
                listOf(TransportKind.BLE, TransportKind.WIFI)
        }

    @Test
    fun `Android offers all three`() = runTest(dispatcher) {
        viewModel().state.value.sections.map { it.kind } shouldContainExactly
            listOf(TransportKind.BLE, TransportKind.SPP, TransportKind.WIFI)
    }

    // --- the honest throughput readout --------------------------------------------------

    @Test
    fun `SessionHealth reaches the screen as a budget, not as a round-trip time`() = runTest(dispatcher) {
        val session = FakeSessionRepository()
        val vm = viewModel(session = session)

        // Nothing measured yet. The screen must not say "0 queries/sec" at every user on every
        // connect, in the second before the first round trip lands.
        vm.state.value.throughput.shouldBeNull()

        // A 70 ms round trip: about fourteen queries a second. The clone from the brief.
        session.emit(
            SessionHealth(
                connection = ConnectionState.CONNECTED,
                capacityHz = 14.3,
                loadHz = 80.0,
                meanRttMs = 70.0,
            ),
        )
        runCurrent()

        vm.state.value.throughput.shouldNotBeNull() shouldBe
            ThroughputAdvice(queriesPerSec = 14, tiles = 6, hz = 2)

        // ...and the dashboard is asking for 80. Say so.
        vm.state.value.health.isOverSubscribed shouldBe true
    }

    // --- failures the user can act on ---------------------------------------------------

    @Test
    fun `a failed connect clears the spinner and surfaces something actionable`() = runTest(dispatcher) {
        val vm = viewModel(connector = FakeObdConnector.failingWith(ConnectFailure.IGNITION_OFF))

        vm.onIntent(ConnectIntent.Select(BLE_ADAPTER))
        runCurrent()

        vm.state.value.let { failed ->
            failed.connectingTo.shouldBeNull()
            failed.ready.shouldBeNull()
            failed.failure shouldBe ConnectFailure.IGNITION_OFF
        }
    }

    @Test
    fun `a permission failure offers the settings deep-link`() = runTest(dispatcher) {
        val vm = viewModel(connector = FakeObdConnector.failingWith(ConnectFailure.BLUETOOTH_PERMISSION))

        vm.effect.test {
            vm.onIntent(ConnectIntent.Select(BLE_ADAPTER))
            runCurrent()
            vm.state.value.failure shouldBe ConnectFailure.BLUETOOTH_PERMISSION

            vm.onIntent(ConnectIntent.OpenSettings)
            awaitItem() shouldBe ConnectEffect.OpenAppSettings
        }
    }

    /**
     * One dead radio must not take the scan down with it. A user whose Bluetooth is off should
     * still be offered the Wi-Fi adapter that is sitting there working.
     */
    @Test
    fun `a transport that fails to scan does not abort the other transports`() = runTest(dispatcher) {
        val connector = FakeObdConnector.readyWith(summary())
        connector.discoveryFailsOn = TransportKind.BLE
        val vm = viewModel(connector = connector)

        vm.onIntent(ConnectIntent.Scan)
        advanceUntilIdle()

        vm.state.value.let { scanned ->
            scanned.isScanning shouldBe false
            scanned.failure.shouldNotBeNull()
            // BLE found nothing, but SPP and Wi-Fi still delivered.
            scanned.sections.single { it.kind == TransportKind.BLE }.adapters.shouldBeEmpty()
            scanned.sections.single { it.kind == TransportKind.WIFI }.adapters.size shouldBe 1
        }
    }

    @Test
    fun `Retry after a failure re-tries the same adapter, not a fresh scan`() = runTest(dispatcher) {
        var attempt = 0
        val connector = FakeObdConnector { _, _ ->
            if (attempt++ == 0) ConnectOutcome.Failed(ConnectFailure.IGNITION_OFF)
            else ConnectOutcome.Ready(summary())
        }
        val vm = viewModel(connector = connector)

        vm.onIntent(ConnectIntent.Select(BLE_ADAPTER))
        runCurrent()
        vm.state.value.failure shouldBe ConnectFailure.IGNITION_OFF

        // The user turned the ignition on. They should not have to find their adapter again.
        vm.onIntent(ConnectIntent.Retry)
        runCurrent()

        vm.state.value.failure.shouldBeNull()
        vm.state.value.ready.shouldNotBeNull().adapter shouldBe BLE_ADAPTER
        connector.calls.map { it.first } shouldContainExactly listOf(BLE_ADAPTER, BLE_ADAPTER)
    }

    /**
     * The quirks are not failures. A clone that overran its buffer and lost the frame-count
     * suffix is a *working* adapter that will be slower, and telling the user `BUFFER FULL`
     * would be telling them a fact they cannot act on, about a session that succeeded.
     */
    @Test
    fun `a clone that degraded still connects — the user never sees an ELM code`() = runTest(dispatcher) {
        val degraded = summary(supportsExpectedFrames = false, isStn = false)
        val vm = viewModel(connector = FakeObdConnector.readyWith(degraded))

        vm.onIntent(ConnectIntent.Select(BLE_ADAPTER))
        runCurrent()

        vm.state.value.failure.shouldBeNull()
        vm.state.value.ready.shouldNotBeNull().isStn shouldBe false
    }

    // --- the learned-quirks table -------------------------------------------------------

    @Test
    fun `a previously-seen adapter is connected with its stored profile, and says so`() = runTest(dispatcher) {
        val known = quirks(BLE_ADAPTER.address, lastUsedMs = 500L)
        val connector = FakeObdConnector.readyWith(summary())
        val vm = viewModel(connector = connector, adapters = FakeAdapterRepository(listOf(known)))

        vm.onIntent(ConnectIntent.Select(BLE_ADAPTER))
        runCurrent()

        // The connector was handed the learned row rather than left to re-derive it. Without
        // this the second connection costs exactly what the first one did — above all the
        // protocol search, which is seconds on a cold bus.
        connector.calls.single().second shouldBe known
        vm.state.value.ready.shouldNotBeNull().reusedProfile shouldBe true
    }

    @Test
    fun `a first connect writes the quirks back, so the next one is cheaper`() = runTest(dispatcher) {
        val repository = FakeAdapterRepository()
        val vm = viewModel(
            connector = FakeObdConnector.readyWith(summary(isStn = true, rttMs = 42)),
            adapters = repository,
        )

        vm.onIntent(ConnectIntent.Select(BLE_ADAPTER))
        runCurrent()

        repository.recall(BLE_ADAPTER.address).shouldNotBeNull().let { saved ->
            saved.isStn shouldBe true
            saved.ewmaRttMs shouldBe 42.0

            // The one that actually buys the speed-up: given it, the next init sends `ATSP6`
            // instead of running a protocol search.
            saved.protocolNum shouldBe 6L

            // Stamped by us, not by the connector — the picker sorts most-recent-first.
            saved.lastUsedMs shouldBe 1_000L
        }
    }

    @Test
    fun `Proceed leaves for the dashboard`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.effect.test {
            vm.onIntent(ConnectIntent.Proceed)
            awaitItem() shouldBe ConnectEffect.NavigateToDashboard
        }
    }

    private companion object {
        val BLE_ADAPTER = DiscoveredAdapter(TransportKind.BLE, "AA:BB:CC:DD:EE:01", "OBDII", rssi = -55)
    }
}
