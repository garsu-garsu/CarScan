package com.bruni.carscan.feature.live

import app.cash.turbine.test
import com.bruni.carscan.core.data.AcquisitionSource
import com.bruni.carscan.core.data.ActiveVehicle
import com.bruni.carscan.core.data.BookmarkRepository
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.data.Settings
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.SignalSeries
import com.bruni.carscan.core.data.ThemeMode
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
import kotlin.test.assertNull
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
    private val bookmarks = FakeBookmarkRepository()
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

    private fun viewModel() = LiveViewModel(session, trips, settings, vehicle, poller, bookmarks)

    private fun LiveViewModel.row(key: MetricKey) = state.value.rows.first { it.key == key }

    /**
     * The one that matters.
     *
     * `LivePlot` reads its revision counter inside the draw lambda precisely so that a sample
     * invalidates the *draw* phase and composition never runs. Holding the samples in `UiState`
     * defeats that from the outside: the `StateFlow` emits, the screen recomposes, and any chart
     * on screen is recomposed by its parent — 20 times a second.
     *
     * So the state object must be *the same instance* after a burst of samples as before it, even
     * though every row is now polled (and accumulating) from the moment the screen opens — no
     * selection is needed first, unlike the old chart.
     */
    @Test
    fun `samples do not touch the UiState`() = runTest {
        val vm = viewModel()
        val before = vm.state.value

        vm.state.test {
            assertSame(before, awaitItem())
            repeat(50) { session.emit(sample(RPM, 800.0 + it)) }
            expectNoEvents()
        }
        assertSame(before, vm.state.value, "a sample must not produce a new state object")
    }

    /**
     * The list is populated from the *vehicle*, not from what has arrived: a signal is not polled
     * until it is a row, so a list fed by the sample stream would start empty and stay empty.
     */
    @Test
    fun `the list is populated before a single sample arrives`() = runTest {
        val vm = viewModel()

        assertEquals(listOf(RPM, SPEED, COOLANT), vm.state.value.available.map { it.key })
    }

    /**
     * (a) A clone adapter has ~15 queries/sec in total. This screen polls *everything* it can ask
     * for the moment it opens — not just what the user has picked, because there is no picker any
     * more — so the very first thing the scheduler hears from a fresh VM must be every key.
     */
    @Test
    fun `on init the poller is told every available key, not a subset`() = runTest {
        val vm = viewModel()

        assertEquals(setOf(RPM, SPEED, COOLANT), poller.visible.last())
        assertEquals(3, vm.state.value.rows.size)
    }

    /**
     * (b) Feeding samples updates a row's latest reading and its running min/avg/max, all in the
     * signal's native unit — see [LiveSeries] for why the conversion is deferred to draw time.
     */
    @Test
    fun `samples update a row's latest reading and running min avg max`() = runTest {
        val vm = viewModel()

        session.emit(sample(RPM, 1000.0))
        session.emit(sample(RPM, 3000.0))
        session.emit(sample(RPM, 2000.0))

        val row = vm.row(RPM)
        assertEquals(2000.0, assertNotNull(row.latest))
        assertEquals(1000.0, assertNotNull(row.min))
        assertEquals(3000.0, assertNotNull(row.max))
        assertEquals(2000.0, assertNotNull(row.avg)) // (1000 + 3000 + 2000) / 3

        val min = assertNotNull(row.min)
        val avg = assertNotNull(row.avg)
        val max = assertNotNull(row.max)
        assertTrue(min <= avg, "min must not exceed avg")
        assertTrue(avg <= max, "avg must not exceed max")
    }

    /** (c) A row nobody has fed a sample to yet has nothing to show — not a zero. */
    @Test
    fun `a row with no samples has fresh, empty stats`() = runTest {
        val vm = viewModel()

        val row = vm.row(RPM)
        assertNull(row.latest)
        assertNull(row.min)
        assertNull(row.avg)
        assertNull(row.max)
    }

    /** (d) Selecting a row opens its detail; closing it clears the detail back out. */
    @Test
    fun `selecting a row opens its detail, and closing it clears the detail`() = runTest {
        val vm = viewModel()

        vm.onIntent(LiveIntent.Select(RPM))
        assertEquals(RPM, assertNotNull(vm.state.value.detail).series.key)

        vm.onIntent(LiveIntent.CloseDetail)
        assertNull(vm.state.value.detail)
    }

    /**
     * (e) The star calls through to the repository, and the row set the screen reads back reflects
     * it — `BookmarkRepository` is the single source of truth, not a flag this VM invents locally.
     */
    @Test
    fun `toggling a bookmark calls the repository, and the state reflects it`() = runTest {
        val vm = viewModel()

        vm.onIntent(LiveIntent.ToggleBookmark(RPM))
        assertTrue(RPM in bookmarks.toggled)
        assertTrue(RPM in vm.state.value.bookmarked)

        vm.onIntent(LiveIntent.ToggleBookmark(RPM))
        assertTrue(RPM !in vm.state.value.bookmarked)
    }

    /**
     * (f) A deep link arrives with a key already chosen — see `Route.Live` and `DashboardEffect.
     * OpenLiveChart` — and must open straight into that signal's detail, with no second step (the
     * old chart needed a `ToggleSeries` after arriving; this screen has already been polling every
     * signal since it opened, so `Select` alone is enough).
     */
    @Test
    fun `arriving with a preselected key opens its detail directly`() = runTest {
        val vm = viewModel()

        vm.onIntent(LiveIntent.Select(SPEED))

        val detail = assertNotNull(vm.state.value.detail)
        assertEquals(SPEED, detail.series.key)
        assertEquals(0, detail.plot.samples.size, "nothing has arrived yet, but the detail is already open")
    }

    /**
     * Conversion happens once, at the presentation edge, and only for the open detail — see
     * [LiveViewModel.onSample]. The sample the repository emitted — and the one on its way to the
     * trip database — stays in the unit OBDb declared it in, so that changing a preference cannot
     * retroactively rewrite recorded history.
     */
    @Test
    fun `a km per h sample reaches the open detail as mph, and the stored sample stays km per h`() = runTest {
        settings.set(UnitPreferences.METRIC.with(Quantity.SPEED, UnitId.MPH))
        val vm = viewModel()
        vm.onIntent(LiveIntent.Select(SPEED))

        session.emit(sample(SPEED, 100.0, ObdUnit.KILOMETERS_PER_HOUR))

        val detail = assertNotNull(vm.state.value.detail)

        // The plot is drawn in mph: the sample and the scale it is drawn against move together.
        // 250 km/h is 155 mph — left native, a 62 mph reading would sit a quarter of the way up a
        // scale that still ended at 250.
        assertEquals(62.137f, detail.plot.samples[0], absoluteTolerance = 0.001f)
        assertEquals(155.34f, detail.max, absoluteTolerance = 0.01f)
        assertEquals(UnitId.MPH, detail.displayUnit)

        // The row keeps the *native* value: `UnitReadout` converts and formats it together at the
        // moment it is drawn, which is the only supported way to put a number on screen.
        assertEquals(100.0, assertNotNull(detail.series.latest), absoluteTolerance = 1e-9)
        assertEquals(ObdUnit.KILOMETERS_PER_HOUR, detail.series.nativeUnit)

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
    fun `a celsius sample reaches the open detail as fahrenheit, offset and all`() = runTest {
        settings.set(UnitPreferences.METRIC.with(Quantity.TEMPERATURE, UnitId.FAHRENHEIT))
        val vm = viewModel()
        vm.onIntent(LiveIntent.Select(COOLANT))

        session.emit(sample(COOLANT, 90.0, ObdUnit.CELSIUS))

        val detail = assertNotNull(vm.state.value.detail)
        assertEquals(194f, detail.plot.samples[0], absoluteTolerance = 0.001f)
        // The range is absolute too: 0–120 °C is 32–248 °F, not 0–216.
        assertEquals(32f, detail.min, absoluteTolerance = 0.01f)
        assertEquals(248f, detail.max, absoluteTolerance = 0.01f)
    }

    /**
     * Changing a unit mid-drive rebuilds the open detail's window. The 30 s it holds are in the
     * old unit and a ring buffer cannot be read back out, so the alternative is a chart with two
     * units on it.
     */
    @Test
    fun `changing the preference re-scales the open detail`() = runTest {
        val vm = viewModel()
        vm.onIntent(LiveIntent.Select(SPEED))
        assertEquals(250f, assertNotNull(vm.state.value.detail).max)

        settings.set(UnitPreferences.METRIC.with(Quantity.SPEED, UnitId.MPH))

        val detail = assertNotNull(vm.state.value.detail)
        assertEquals(155.34f, detail.max, absoluteTolerance = 0.01f)
        assertEquals(UnitId.MPH, detail.displayUnit)
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
    fun `the 601st sample evicts the oldest from the open detail`() = runTest {
        val vm = viewModel()
        vm.onIntent(LiveIntent.Select(RPM))
        val plot = assertNotNull(vm.state.value.detail).plot

        repeat(LIVE_PLOT_DEFAULT_CAPACITY + 1) { session.emit(sample(RPM, it.toDouble())) }

        assertEquals(LIVE_PLOT_DEFAULT_CAPACITY, plot.samples.size, "the window must not grow")
        // Sample 0 is gone; the window now starts at 1 and ends at the newest.
        assertEquals(1f, plot.samples[0])
        assertEquals(LIVE_PLOT_DEFAULT_CAPACITY.toFloat(), plot.samples[plot.samples.size - 1])
    }

    /**
     * A sample for a row that is not the open detail must still update that row's stats — "poll
     * everything" means every row accumulates — but must not push into the *other* row's plot, or
     * a rapid succession of selections would leave stale points from a signal never selected.
     */
    @Test
    fun `a sample for a row that is not the detail updates only its own stats, never the detail's plot`() = runTest {
        val vm = viewModel()
        vm.onIntent(LiveIntent.Select(RPM))

        session.emit(sample(SPEED, 100.0, ObdUnit.KILOMETERS_PER_HOUR))

        assertEquals(0, assertNotNull(vm.state.value.detail).plot.samples.size)
        assertEquals(100.0, assertNotNull(vm.row(SPEED).latest))
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
    override suspend fun setThemeMode(mode: ThemeMode) = Unit
    override suspend fun setGaugeStyle(style: String) = Unit
    override suspend fun setAutoReconnect(enabled: Boolean) = Unit
    override suspend fun setAcquisitionSource(source: AcquisitionSource) = Unit
    override suspend fun setAutoDriveDetectSpeedKmh(kmh: Int) = Unit
    override suspend fun setBackgroundTracking(enabled: Boolean) = Unit
}

private class FakeTrips : TripRepository {
    val series = mutableMapOf<Pair<String, String>, SignalSeries>()

    override val isRecording: Boolean get() = false
    override val activeTrip: StateFlow<com.bruni.carscan.core.data.ActiveTrip?> =
        MutableStateFlow(null).asStateFlow()
    override suspend fun series(tripId: String, signalId: String): SignalSeries? =
        series[tripId to signalId]

    override suspend fun start(vehicleId: String?, startedMs: Long, source: String): String = "trip-1"
    override fun offer(sample: SensorSample) = Unit
    override suspend fun stop(endedMs: Long) = Unit
    override suspend fun trips(vehicleId: String): List<TripSummary> = emptyList()
    override suspend fun allTrips(): List<TripSummary> = emptyList()
    override suspend fun summary(tripId: String): TripSummary? = null
    override suspend fun signalIds(tripId: String): List<String> = series.keys.map { it.second }
    override suspend fun setStartLocation(tripId: String, lat: Double, lon: Double, address: String?) = Unit
    override suspend fun setEndLocation(tripId: String, lat: Double, lon: Double, address: String?) = Unit
    override suspend fun import(trip: TripSummary, series: List<SignalSeries>) = Unit
    override suspend fun delete(tripId: String) = Unit
}

/** A real one, not a spy: `toggle` actually flips membership, so the state read-back means something. */
private class FakeBookmarkRepository : BookmarkRepository {
    private val state = MutableStateFlow(emptySet<MetricKey>())
    val toggled = mutableListOf<MetricKey>()

    override val bookmarks: Flow<Set<MetricKey>> = state

    override suspend fun toggle(key: MetricKey) {
        toggled += key
        state.update { if (key in it) it - key else it + key }
    }

    override suspend fun isBookmarked(key: MetricKey): Boolean = key in state.value
}
