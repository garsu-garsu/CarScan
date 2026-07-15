package com.bruni.carscan.feature.dashboard

import app.cash.turbine.test
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class DashboardViewModelTest {

    // 0D = vehicle speed (km/h, `speed`, 4 Hz). 0C = RPM, which has no suggestedMetric and is
    // therefore only addressable as MetricKey.Signal — the reason MetricKey exists. 0142 =
    // control module voltage on 7E4, the command the poller will strike off as unsupported.
    private val speedCommand = command(
        hdr = "7E0",
        pid = "0D",
        freq = 0.25,
        signals = arrayOf(
            signal(
                "VSS",
                name = "Vehicle speed",
                metric = SuggestedMetric.SPEED,
                unit = ObdUnit.KILOMETERS_PER_HOUR,
                max = 255.0,
            ),
        ),
    )
    private val rpmCommand = command(
        hdr = "7E0",
        pid = "0C",
        freq = 0.1,
        signals = arrayOf(signal("RPM", name = "Engine RPM", unit = ObdUnit.RPM, max = 8000.0)),
    )
    private val voltsCommand = command(
        hdr = "7E4",
        pid = "42",
        freq = 1.0,
        signals = arrayOf(
            signal(
                "VOLT",
                name = "Control module voltage",
                metric = SuggestedMetric.STARTER_BATTERY_VOLTAGE,
                unit = ObdUnit.VOLTS,
                max = 16.0,
            ),
        ),
    )

    private val volts = MetricKey.Metric(SuggestedMetric.STARTER_BATTERY_VOLTAGE)

    private val effective = EffectiveSignalset.of(
        standard = signalset(speedCommand, rpmCommand, voltsCommand),
        vehicle = signalset(),
        modelYear = 2023,
    )

    /** Header-qualified, as the poller reports it: "7E4.0142". */
    private val voltsCommandId = effective[volts].single().command.id

    private val session = FakeSession()
    private val layouts = FakeLayouts()
    private val settings = FakeSettings()
    private val vehicle = FakeActiveVehicle(effective)
    private val poller = FakeVisibleSignals()
    private val clock = FakeClock()

    /** Driven by hand: no test here depends on a wall clock or on the real ticker. */
    private val ticks = MutableSharedFlow<Long>(replay = 1)

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = DashboardViewModel(
        session = session,
        layouts = layouts,
        settings = settings,
        vehicle = vehicle,
        visibility = poller,
        clock = clock,
        ticks = ticks,
    ).also { advanceUntilIdle() }

    private suspend fun TestScope.tick() {
        ticks.emit(clock.now)
        advanceUntilIdle()
    }

    /** Emits a sample stamped with the current wall clock — i.e. one that has just arrived. */
    private fun emitNow(key: MetricKey, value: Double, unit: ObdUnit, signalId: String) {
        session.emit(sample(key, value, unit, signalId, timestampMs = clock.now))
    }

    /** Adds tiles and returns their ids, in grid order. */
    private fun TestScope.add(vm: DashboardViewModel, vararg keys: MetricKey): List<String> {
        keys.forEach { vm.onIntent(DashboardIntent.Add(it)) }
        advanceUntilIdle()
        return vm.state.value.tiles.map { it.id }
    }

    // --- the layout persists ---------------------------------------------------

    @Test
    fun `adding, removing and reordering tiles round-trips through the persisted layout`() =
        runTest(dispatcher) {
            val vm = viewModel()

            vm.onIntent(DashboardIntent.Add(SPEED_KEY))
            vm.onIntent(DashboardIntent.Add(RPM_KEY))
            vm.onIntent(DashboardIntent.Add(volts))
            advanceUntilIdle()

            vm.onIntent(DashboardIntent.Remove(vm.state.value.tiles[1].id))   // drop RPM
            advanceUntilIdle()
            vm.onIntent(DashboardIntent.Move(0, 1))                            // speed below volts
            advanceUntilIdle()

            vm.state.value.tiles.map { it.key } shouldContainExactly listOf(volts, SPEED_KEY)

            // The assertion that matters: a *fresh* ViewModel over the same repository comes back
            // with the same dashboard. Asserting on the first one's state would pass even if
            // nothing had ever been written.
            viewModel().state.value.tiles.map { it.key } shouldContainExactly
                listOf(volts, SPEED_KEY)
        }

    @Test
    fun `a tile keeps the gauge style it was given`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onIntent(DashboardIntent.Add(RPM_KEY))
        advanceUntilIdle()
        vm.onIntent(
            DashboardIntent.SetStyle(vm.state.value.tiles.single().id, GaugeStyleId.CLASSIC_ANALOG),
        )
        advanceUntilIdle()

        viewModel().state.value.tiles.single().style shouldBe GaugeStyleId.CLASSIC_ANALOG
    }

    // --- the poller is told what is on screen -----------------------------------

    /**
     * The test the brief asks to fail if the call is removed. `FakeVisibleSignals.visible` starts
     * at **null**, not at the empty set, so a ViewModel that never calls `setVisible` cannot
     * satisfy this by accident.
     */
    @Test
    fun `setVisible is called with exactly the tiles on screen`() = runTest(dispatcher) {
        val vm = viewModel()
        val ids = add(vm, SPEED_KEY, RPM_KEY, volts)
        advanceUntilIdle()

        poller.visible.test {
            vm.onIntent(DashboardIntent.VisibleTiles(listOf(ids[0], ids[1])))
            advanceUntilIdle()
            expectMostRecentItem() shouldBe setOf(SPEED_KEY, RPM_KEY)
        }
    }

    /**
     * Scrolling a tile off the screen must demote it. Without this the scheduler goes on spending
     * a clone adapter's 15 queries/sec on PIDs nobody is looking at, and the visible gauges crawl.
     */
    @Test
    fun `scrolling a tile out of view demotes it`() = runTest(dispatcher) {
        val vm = viewModel()
        val ids = add(vm, SPEED_KEY, RPM_KEY, volts)
        advanceUntilIdle()

        vm.onIntent(DashboardIntent.VisibleTiles(listOf(ids[0], ids[1])))
        advanceUntilIdle()
        poller.visible.value shouldBe setOf(SPEED_KEY, RPM_KEY)

        vm.onIntent(DashboardIntent.VisibleTiles(listOf(ids[1], ids[2])))
        advanceUntilIdle()
        poller.visible.value shouldBe setOf(RPM_KEY, volts)
    }

    /** The grid re-reports its visible items on every scroll frame. The poller must not see that. */
    @Test
    fun `an unchanged visible set is not re-announced`() = runTest(dispatcher) {
        val vm = viewModel()
        val ids = add(vm, SPEED_KEY, RPM_KEY)
        advanceUntilIdle()

        vm.onIntent(DashboardIntent.VisibleTiles(ids))
        advanceUntilIdle()
        val calls = poller.calls.size

        repeat(5) { vm.onIntent(DashboardIntent.VisibleTiles(ids)) }
        advanceUntilIdle()

        poller.calls.size shouldBe calls
    }

    @Test
    fun `removing a visible tile takes it out of the visible set`() = runTest(dispatcher) {
        val vm = viewModel()
        val ids = add(vm, SPEED_KEY, RPM_KEY)
        advanceUntilIdle()
        vm.onIntent(DashboardIntent.VisibleTiles(ids))
        advanceUntilIdle()

        vm.onIntent(DashboardIntent.Remove(ids[0]))
        advanceUntilIdle()

        poller.visible.value shouldBe setOf(RPM_KEY)
    }

    // --- units are converted exactly once, here ---------------------------------

    @Test
    fun `a km per hour sample with an mph preference reaches state as mph, and the sample is untouched`() =
        runTest(dispatcher) {
            settings.setUnits(UnitPreferences.defaultsFor("en-US"))
            val vm = viewModel()
            vm.onIntent(DashboardIntent.Add(SPEED_KEY))
            advanceUntilIdle()

            emitNow(SPEED_KEY, 100.0, ObdUnit.KILOMETERS_PER_HOUR, "VSS")
            tick()

            vm.state.test {
                val tile = expectMostRecentItem().tiles.single()
                tile.spec.value shouldBe (62.137f plusOrMinus 0.01f)
                tile.displayUnit shouldBe UnitId.MPH
                // The end stops move with the needle. VSS declares max 255 km/h, so the dial runs
                // to 158 mph — not 255, which would draw 62 mph a quarter of the way round a dial
                // labelled in the wrong unit.
                tile.spec.max shouldBe (158.448f plusOrMinus 0.01f)
            }

            // Storage, the poller and the ring buffer are all native. The repository still holds
            // exactly what the car said, in the unit the car said it in.
            val stored = session.latestSamples.value.getValue(SPEED_KEY)
            (stored.value as DecodedValue.Numeric).value shouldBe 100.0
            stored.unit shouldBe ObdUnit.KILOMETERS_PER_HOUR
        }

    @Test
    fun `the same sample reaches state as km per hour under a metric preference`() =
        runTest(dispatcher) {
            settings.setUnits(UnitPreferences.METRIC)
            val vm = viewModel()
            vm.onIntent(DashboardIntent.Add(SPEED_KEY))
            advanceUntilIdle()

            emitNow(SPEED_KEY, 100.0, ObdUnit.KILOMETERS_PER_HOUR, "VSS")
            tick()

            val tile = vm.state.value.tiles.single()
            tile.spec.value shouldBe 100f
            tile.displayUnit shouldBe UnitId.KMH
        }

    // --- stale is not zero -------------------------------------------------------

    @Test
    fun `a tile with no sample is stale, and stops being stale on the first reading`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(DashboardIntent.Add(SPEED_KEY))
            advanceUntilIdle()
            tick()

            vm.state.value.tiles.single().spec.isStale shouldBe true

            emitNow(SPEED_KEY, 0.0, ObdUnit.KILOMETERS_PER_HOUR, "VSS")
            tick()

            val spec = vm.state.value.tiles.single().spec
            spec.isStale shouldBe false
            spec.value shouldBe 0f   // a car stopped at a light is a reading, not a missing one
        }

    /**
     * The sentinel case, end to end. An OBDb `nullmin`/`nullmax` sentinel makes `SignalDecoder`
     * return null and `PollDecoder` emit **nothing at all**, so the repository goes on handing us
     * the last good sample forever. Only the age of that sample can reveal an unplugged sensor.
     */
    @Test
    fun `a tile whose samples stop arriving goes stale rather than showing the last value as live`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onIntent(DashboardIntent.Add(SPEED_KEY))
            advanceUntilIdle()

            val readAt = clock.now
            emitNow(SPEED_KEY, 90.0, ObdUnit.KILOMETERS_PER_HOUR, "VSS")
            tick()
            vm.state.value.tiles.single().spec.isStale shouldBe false

            // The ECU starts answering with the sentinel. No sample is emitted at all, so `latest`
            // never changes and goes on handing us the 90 km/h reading, indefinitely.
            clock.now = readAt + MIN_STALENESS_WINDOW_MS + 1
            tick()

            val spec = vm.state.value.tiles.single().spec
            spec.isStale shouldBe true
            spec.value shouldBe 90f   // not 0 — "the car stopped" is a worse lie than "no reading"
        }

    // --- the picker only offers what this car can answer --------------------------

    @Test
    fun `the picker offers the signals the vehicle supports`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.state.value.available.map { it.label } shouldContainExactly
            listOf("Control module voltage", "Engine RPM", "Vehicle speed")
    }

    @Test
    fun `the picker excludes commands the poller marked unsupported`() = runTest(dispatcher) {
        vehicle.unsupported.value = setOf(voltsCommandId)
        val vm = viewModel()
        advanceUntilIdle()

        vm.state.value.available.map { it.key }.contains(volts) shouldBe false
        vm.state.value.available.map { it.label } shouldContainExactly
            listOf("Engine RPM", "Vehicle speed")
    }

    /** A 2023 EV6 reports state of charge four ways. Losing one command is not losing the metric. */
    @Test
    fun `a key with a second, still-supported source stays in the picker`() = runTest(dispatcher) {
        val alternate = command(
            hdr = "7E4",
            pid = "43",
            signals = arrayOf(
                signal(
                    "VOLT2",
                    name = "Battery voltage",
                    metric = SuggestedMetric.STARTER_BATTERY_VOLTAGE,
                    unit = ObdUnit.VOLTS,
                    max = 16.0,
                ),
            ),
        )
        vehicle.signalset.value = EffectiveSignalset.of(
            standard = signalset(voltsCommand, alternate),
            vehicle = signalset(),
            modelYear = 2023,
        )
        vehicle.unsupported.value = setOf(voltsCommandId)

        val vm = viewModel()
        advanceUntilIdle()
        vm.state.value.available.map { it.key }.contains(volts) shouldBe true
    }

    @Test
    fun `a new tile takes its label, unit and range from the signalset`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onIntent(DashboardIntent.Add(RPM_KEY))
        advanceUntilIdle()

        val tile = vm.state.value.tiles.single()
        tile.spec.label shouldBe "Engine RPM"
        tile.spec.max shouldBe 8000f
        // rpm is a unit the app offers no choice in — shown exactly as the car decoded it.
        tile.displayUnit shouldBe null
        tile.nativeUnit shouldBe ObdUnit.RPM
    }

    @Test
    fun `tapping a tile asks to open its live chart`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onIntent(DashboardIntent.Add(RPM_KEY))
        advanceUntilIdle()

        vm.effect.test {
            vm.onIntent(DashboardIntent.TileTapped(vm.state.value.tiles.single().id))
            advanceUntilIdle()
            awaitItem() shouldBe DashboardEffect.OpenLiveChart(RPM_KEY)
        }
    }

    @Test
    fun `the HUD action asks to open the HUD rather than navigating itself`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.effect.test {
            vm.onIntent(DashboardIntent.OpenHud)
            advanceUntilIdle()
            awaitItem() shouldBe DashboardEffect.OpenHud
        }
    }
}
