package com.bruni.carscan.feature.live

import app.cash.turbine.test
import com.bruni.carscan.core.data.ActiveVehicle
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.SignalSeries
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.data.TripSummary
import com.bruni.carscan.core.data.VehicleSessionRepository
import com.bruni.carscan.core.data.VisibleSignals
import com.bruni.carscan.core.designsystem.chart.LIVE_PLOT_DEFAULT_CAPACITY
import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.model.asDoubleOrNull
import com.bruni.carscan.core.units.Quantity
import com.bruni.carscan.core.units.SpeedUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val RPM = MetricKey.Signal("RPM")
private val SPEED = MetricKey.Metric(SuggestedMetric.SPEED)
private val COOLANT = MetricKey.Metric(SuggestedMetric.ENGINE_COOLANT_TEMPERATURE)

class LiveViewModelTest {

    private val session = FakeSession()
    private val trips = FakeTrips()
    private val settings = FakeSettings()
    private val poller = RecordingPoller()
    private val vehicle = FakeVehicle(
        signalset(
            signal("RPM", name = "Engine RPM", max = 8000.0),
            signal("VSS", name = "Speed", max = 250.0, unit = ObdUnit.KILOMETERS_PER_HOUR, metric = SuggestedMetric.SPEED),
            signal(
                "ECT", name = "Coolant", max = 120.0, unit = ObdUnit.CELSIUS,
                metric = SuggestedMetric.ENGINE_COOLANT_TEMPERATURE,
            ),
        ),
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = LiveViewModel(session, trips, settings, vehicle, poller)

    /**
     * The one that matters.
     *
     * `LivePlot` reads its revision counter inside the draw lambda precisely so that a sample
     * invalidates the *draw* phase and composition never runs. Holding the samples in `UiState`
     * defeats that from the outside: the `StateFlow` emits, the screen recomposes, and the chart
     * that was so careful not to recompose is recomposed by its parent — 20 times a second.
     *
     * So the state object must be *the same instance* after a burst of samples as before it.
     * Put a sample list, a value, or even a counter in `LiveUiState` and this fails.
     */
    @Test
    fun `samples do not touch the UiState`() = runTest {
        val vm = viewModel()
        vm.onIntent(LiveIntent.ToggleSeries(RPM))

        val before = vm.state.value

        vm.state.test {
            assertSame(before, awaitItem())
            repeat(50) { session.emit(sample(RPM, 800.0 + it)) }
            expectNoEvents()
        }
        assertSame(before, vm.state.value, "a sample must not produce a new state object")
    }

    /**
     * The picker lists what the *vehicle* can be asked for, not what has already arrived.
     *
     * Populating it from the sample stream is circular and self-defeating: a signal is not polled
     * until it is charted, and it cannot be charted until it appears in the picker — so a picker
     * fed by arriving samples starts empty and stays empty.
     */
    @Test
    fun `the picker is populated before a single sample arrives`() = runTest {
        val vm = viewModel()

        assertEquals(listOf(RPM, SPEED, COOLANT), vm.state.value.available.map { it.key })
    }

    /**
     * A clone adapter has ~15 queries/sec in total. Spending them on series nobody is looking at
     * is why a dashboard feels broken, so the scheduler has to be told what is on screen — and
     * told again the moment that changes.
     */
    @Test
    fun `the poller is told exactly which series are charted`() = runTest {
        val vm = viewModel()

        vm.onIntent(LiveIntent.ToggleSeries(RPM))
        assertEquals(setOf(RPM), poller.visible.last())

        vm.onIntent(LiveIntent.ToggleSeries(SPEED))
        assertEquals(setOf(RPM, SPEED), poller.visible.last())

        // Deselecting demotes it again: a series scrolled off the screen must stop costing a
        // query, which is the whole point of telling the scheduler in the first place.
        vm.onIntent(LiveIntent.ToggleSeries(RPM))
        assertEquals(setOf(SPEED), poller.visible.last())
    }

    /**
     * Conversion happens once, at the presentation edge. The sample the repository emitted — and
     * the one on its way to the trip database — stays in the unit OBDb declared it in, so that
     * changing a preference cannot retroactively rewrite recorded history.
     */
    @Test
    fun `a km per h sample reaches the UI as mph, and the stored sample stays km per h`() = runTest {
        settings.set(UnitPreferences.METRIC.with(Quantity.SPEED, UnitId.MPH))
        val vm = viewModel()
        vm.onIntent(LiveIntent.ToggleSeries(SPEED))

        session.emit(sample(SPEED, 100.0, ObdUnit.KILOMETERS_PER_HOUR))

        val charted = vm.state.value.charted.single()

        // The chart is drawn in mph: the sample and the scale it is drawn against move together.
        // 250 km/h is 155 mph — left native, a 62 mph reading would sit a quarter of the way up a
        // scale that still ended at 250.
        assertEquals(62.137f, charted.plot.samples[0], absoluteTolerance = 0.001f)
        assertEquals(155.34f, charted.max, absoluteTolerance = 0.01f)
        assertEquals(UnitId.MPH, charted.displayUnit)

        // The readout keeps the *native* value: `UnitReadout` converts and formats it together at
        // the moment it is drawn, which is the only supported way to put a number on screen.
        assertEquals(100.0, assertNotNull(charted.latest), absoluteTolerance = 1e-9)
        assertEquals(ObdUnit.KILOMETERS_PER_HOUR, charted.nativeUnit)

        // And the sample the repository emitted — the one on its way to the trip database — is
        // untouched, so changing a preference cannot rewrite recorded history.
        val stored = assertNotNull(session.latest.value[SPEED])
        assertEquals(100.0, assertNotNull(stored.value.asDoubleOrNull), absoluteTolerance = 1e-9)
        assertEquals(ObdUnit.KILOMETERS_PER_HOUR, stored.unit)
    }

    /**
     * The affine trap, and the reason `:core:units` has two conversion functions.
     *
     * 90 °C is 194 °F. `convertDelta` would say 162 — correct for a temperature *rise* of 90 °C,
     * and badly wrong for a coolant gauge. Both numbers are plausible on a dashboard, which is
     * exactly why this needs a test rather than care.
     */
    @Test
    fun `a celsius sample reaches the UI as fahrenheit, offset and all`() = runTest {
        settings.set(UnitPreferences.METRIC.with(Quantity.TEMPERATURE, UnitId.FAHRENHEIT))
        val vm = viewModel()
        vm.onIntent(LiveIntent.ToggleSeries(COOLANT))

        session.emit(sample(COOLANT, 90.0, ObdUnit.CELSIUS))

        val charted = vm.state.value.charted.single()
        assertEquals(194f, charted.plot.samples[0], absoluteTolerance = 0.001f)
        // The range is absolute too: 0–120 °C is 32–248 °F, not 0–216.
        assertEquals(32f, charted.min, absoluteTolerance = 0.01f)
        assertEquals(248f, charted.max, absoluteTolerance = 0.01f)
    }

    /** The same sample, with the default preference, is not converted at all. */
    @Test
    fun `a km per h sample stays km per h when that is the preference`() = runTest {
        val vm = viewModel()
        vm.onIntent(LiveIntent.ToggleSeries(SPEED))

        session.emit(sample(SPEED, 100.0, ObdUnit.KILOMETERS_PER_HOUR))

        val charted = vm.state.value.charted.single()
        assertEquals(100f, charted.plot.samples[0], absoluteTolerance = 1e-6f)
        assertEquals(250f, charted.max)
    }

    /**
     * Engine RPM carries no unit — OBDb declares none, and there is no preference to convert to.
     * It must reach the chart exactly as decoded, whatever the user's other units are.
     */
    @Test
    fun `a unitless signal is never converted`() = runTest {
        settings.set(
            UnitPreferences.METRIC
                .with(Quantity.SPEED, UnitId.MPH)
                .with(Quantity.TEMPERATURE, UnitId.FAHRENHEIT),
        )
        val vm = viewModel()
        vm.onIntent(LiveIntent.ToggleSeries(RPM))

        session.emit(sample(RPM, 1726.0))

        val charted = vm.state.value.charted.single()
        assertEquals(1726f, charted.plot.samples[0], absoluteTolerance = 1e-6f)
        assertEquals(1726.0, assertNotNull(charted.latest), absoluteTolerance = 1e-9)
        assertEquals(8000f, charted.max)
        assertEquals(null, charted.displayUnit)
    }

    /**
     * Changing a unit mid-drive rebuilds the windows. The 30 s they hold are in the old unit and a
     * ring buffer cannot be read back out, so the alternative is a chart with two units on it.
     */
    @Test
    fun `changing the preference re-scales the charted series`() = runTest {
        val vm = viewModel()
        vm.onIntent(LiveIntent.ToggleSeries(SPEED))
        assertEquals(250f, vm.state.value.charted.single().max)

        settings.set(UnitPreferences.METRIC.with(Quantity.SPEED, UnitId.MPH))

        val charted = vm.state.value.charted.single()
        assertEquals(155.34f, charted.max, absoluteTolerance = 0.01f)
        assertEquals(UnitId.MPH, charted.displayUnit)
    }

    /**
     * The window rolls. It does not grow.
     *
     * An eight-hour drive at 20 Hz is half a million samples; a chart backed by a growing list is
     * an out-of-memory crash with a timetable, and the GC pauses arrive long before the crash — in
     * the one place the user is watching a line move. So the 601st sample into a 600-slot window
     * evicts the first, and what remains is the *most recent* 600, still in order.
     */
    @Test
    fun `the 601st sample evicts the oldest`() = runTest {
        val vm = viewModel()
        vm.onIntent(LiveIntent.ToggleSeries(RPM))
        val plot = vm.state.value.charted.single().plot

        repeat(LIVE_PLOT_DEFAULT_CAPACITY + 1) { session.emit(sample(RPM, it.toDouble())) }

        assertEquals(LIVE_PLOT_DEFAULT_CAPACITY, plot.samples.size, "the window must not grow")
        // Sample 0 is gone; the window now starts at 1 and ends at the newest.
        assertEquals(1f, plot.samples[0])
        assertEquals(LIVE_PLOT_DEFAULT_CAPACITY.toFloat(), plot.samples[plot.samples.size - 1])
    }

    /** The samples reach the *chart* converted, not just the readout beside it. */
    @Test
    fun `the chart is fed the converted value, not the native one`() = runTest {
        settings.set(UnitPreferences.METRIC.with(Quantity.SPEED, UnitId.MPH))
        val vm = viewModel()
        vm.onIntent(LiveIntent.ToggleSeries(SPEED))

        session.emit(sample(SPEED, 100.0, ObdUnit.KILOMETERS_PER_HOUR))

        val plot = vm.state.value.charted.single().plot
        assertEquals(1, plot.samples.size)
        assertEquals(62.137f, plot.samples[0], absoluteTolerance = 0.001f)
    }

    /** A sample for a series nobody selected must not be charted — or the ring buffers fill up. */
    @Test
    fun `an unselected series is not charted`() = runTest {
        val vm = viewModel()
        vm.onIntent(LiveIntent.ToggleSeries(RPM))

        session.emit(sample(SPEED, 100.0, ObdUnit.KILOMETERS_PER_HOUR))

        assertEquals(listOf(RPM), vm.state.value.charted.map { it.key })
    }

    /** Loading a trip's stored series charts it with its gaps intact. */
    @Test
    fun `history loads a trip signal with its gaps preserved`() = runTest {
        trips.series["trip-1" to "RPM"] =
            SignalSeries("RPM", floatArrayOf(800f, 900f, Float.NaN, 1200f))
        val vm = viewModel()

        vm.onIntent(LiveIntent.ShowHistory(tripId = "trip-1", signalId = "RPM"))

        val history = assertNotNull(vm.state.value.history)
        assertEquals(2, history.segments.size)
        assertTrue(history.segments.none { seg -> seg.any { it.second == 2 } })
    }
}

private fun sample(key: MetricKey, value: Double, unit: ObdUnit? = null) = SensorSample(
    signalId = when (key) {
        is MetricKey.Signal -> key.signalId
        is MetricKey.Metric -> key.metric.name
    },
    key = key,
    value = DecodedValue.Numeric(value),
    unit = unit,
    timestampMs = 0L,
)

private class FakeSession : VehicleSessionRepository {
    private val _samples = MutableSharedFlow<SensorSample>(extraBufferCapacity = 1024)
    override val samples: SharedFlow<SensorSample> = _samples.asSharedFlow()

    private val _latest = MutableStateFlow<Map<MetricKey, SensorSample>>(emptyMap())
    override val latest: StateFlow<Map<MetricKey, SensorSample>> = _latest.asStateFlow()

    override val health: StateFlow<SessionHealth> = MutableStateFlow(SessionHealth())

    suspend fun emit(sample: SensorSample) {
        _latest.update { it + (sample.key to sample) }
        _samples.emit(sample)
    }
}

private class RecordingPoller : VisibleSignals {
    val visible = mutableListOf<Set<MetricKey>>()
    override fun setVisible(keys: Set<MetricKey>) {
        visible += keys
    }
}

private class FakeVehicle(effective: EffectiveSignalset?) : ActiveVehicle {
    override val signalset = MutableStateFlow(effective)
    override val unsupported = MutableStateFlow(emptySet<String>())
}

private class FakeSettings : SettingsRepository {
    private val flow = MutableStateFlow(Settings())
    override val settings: Flow<Settings> = flow

    fun set(units: UnitPreferences) {
        flow.update { it.copy(units = units) }
    }

    override suspend fun setUnit(quantity: Quantity, unit: UnitId) {
        flow.update { it.copy(units = it.units.with(quantity, unit)) }
    }

    override suspend fun setUnits(units: UnitPreferences) = set(units)

    @Deprecated("Use setUnit(Quantity.SPEED, …).", ReplaceWith("setUnit(Quantity.SPEED, unit)"))
    override suspend fun setSpeedUnit(unit: SpeedUnit) =
        setUnit(Quantity.SPEED, if (unit == SpeedUnit.MILES_PER_HOUR) UnitId.MPH else UnitId.KMH)

    override suspend fun setRecordTrips(enabled: Boolean) = Unit
    override suspend fun setKeepScreenOn(enabled: Boolean) = Unit
    override suspend fun setActiveVehicleId(id: String?) = Unit
}

private class FakeTrips : TripRepository {
    val series = mutableMapOf<Pair<String, String>, SignalSeries>()

    override val isRecording: Boolean get() = false
    override suspend fun series(tripId: String, signalId: String): SignalSeries? =
        series[tripId to signalId]

    override suspend fun start(vehicleId: String, startedMs: Long): String = "trip-1"
    override fun offer(sample: SensorSample) = Unit
    override suspend fun stop(endedMs: Long) = Unit
    override suspend fun trips(vehicleId: String): List<TripSummary> = emptyList()
    override suspend fun summary(tripId: String): TripSummary? = null
    override suspend fun signalIds(tripId: String): List<String> = series.keys.map { it.second }
    override suspend fun import(trip: TripSummary, series: List<SignalSeries>) = Unit
    override suspend fun delete(tripId: String) = Unit
}
