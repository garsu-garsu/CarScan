package com.bruni.carscan.feature.hud

import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.core.vehicle.EffectiveSignalset
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

class HudViewModelTest {

    // 0D = vehicle speed (km/h, `speed`). 0C = RPM, which has no suggestedMetric and is only
    // addressable as MetricKey.Signal("RPM") — see HUD_RPM_KEY.
    private val speedCommand = command(
        hdr = "7E0",
        pid = "0D",
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
        signals = arrayOf(signal("RPM", name = "Engine RPM", unit = ObdUnit.RPM, max = 8000.0)),
    )

    private val effective = EffectiveSignalset.of(
        standard = signalset(speedCommand, rpmCommand),
        vehicle = signalset(),
        modelYear = 2023,
    )

    private val session = FakeSession()
    private val settings = FakeSettings()
    private val vehicle = FakeActiveVehicle(effective)
    private val visibility = FakeVisibleSignals()

    /** Driven by hand: no test here depends on a wall clock or the real 500ms ticker. */
    private var currentTime = BASE_TIME
    private val now: () -> Long = { currentTime }
    private val ticks = MutableSharedFlow<Long>(replay = 1)

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = HudViewModel(
        session = session,
        vehicle = vehicle,
        settings = settings,
        visibility = visibility,
        now = now,
        ticks = ticks,
    ).also { advanceUntilIdle() }

    private suspend fun TestScope.tick() {
        ticks.emit(now())
        advanceUntilIdle()
    }

    @Test
    fun `setVisible is called once, on init, with exactly the two fixed keys`() = runTest(dispatcher) {
        viewModel()

        visibility.calls.size shouldBe 1
        visibility.calls.single() shouldBe setOf(SPEED_KEY, RPM_KEY)
    }

    @Test
    fun `a km per hour speed sample reaches state converted to the user's mph preference`() =
        runTest(dispatcher) {
            settings.setUnits(UnitPreferences.defaultsFor("en-US"))
            val vm = viewModel()

            session.emit(sample(SPEED_KEY, 100.0, ObdUnit.KILOMETERS_PER_HOUR, "VSS", timestampMs = currentTime))
            tick()

            val reading = vm.state.value.readings.single { it.key == SPEED_KEY }
            reading.spec.value shouldBe (62.137f plusOrMinus 0.01f)
            reading.displayUnit shouldBe UnitId.MPH
        }

    @Test
    fun `the same sample reaches state as km per hour under a metric preference`() = runTest(dispatcher) {
        settings.setUnits(UnitPreferences.METRIC)
        val vm = viewModel()

        session.emit(sample(SPEED_KEY, 100.0, ObdUnit.KILOMETERS_PER_HOUR, "VSS", timestampMs = currentTime))
        tick()

        val reading = vm.state.value.readings.single { it.key == SPEED_KEY }
        reading.spec.value shouldBe 100f
        reading.displayUnit shouldBe UnitId.KMH
    }

    @Test
    fun `a fresh sample is not stale and carries its value`() = runTest(dispatcher) {
        val vm = viewModel()

        session.emit(sample(SPEED_KEY, 42.0, ObdUnit.KILOMETERS_PER_HOUR, "VSS", timestampMs = currentTime))
        tick()

        val reading = vm.state.value.readings.single { it.key == SPEED_KEY }
        reading.spec.isStale shouldBe false
        reading.spec.value shouldBe 42f
    }

    @Test
    fun `a reading with no sample yet is stale`() = runTest(dispatcher) {
        val vm = viewModel()
        tick()

        vm.state.value.readings.single { it.key == RPM_KEY }.spec.isStale shouldBe true
    }

    @Test
    fun `a sample older than the staleness window goes stale without dropping to zero`() =
        runTest(dispatcher) {
            val vm = viewModel()

            val readAt = currentTime
            session.emit(sample(SPEED_KEY, 42.0, ObdUnit.KILOMETERS_PER_HOUR, "VSS", timestampMs = readAt))
            tick()
            vm.state.value.readings.single { it.key == SPEED_KEY }.spec.isStale shouldBe false

            currentTime = readAt + STALE_WINDOW_MS + 1
            tick()

            val spec = vm.state.value.readings.single { it.key == SPEED_KEY }.spec
            spec.isStale shouldBe true
            spec.value shouldBe 42f   // "the car stopped" is a worse lie than "no reading"
        }

    private companion object {
        const val BASE_TIME = 1_700_000_000_000L
    }
}
