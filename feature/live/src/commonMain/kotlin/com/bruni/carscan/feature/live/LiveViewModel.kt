package com.bruni.carscan.feature.live

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.data.ActiveVehicle
import com.bruni.carscan.core.data.BookmarkRepository
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.data.VehicleSessionRepository
import com.bruni.carscan.core.data.VisibleSignals
import com.bruni.carscan.core.designsystem.chart.LIVE_PLOT_DEFAULT_CAPACITY
import com.bruni.carscan.core.designsystem.chart.LivePlotState
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.asDoubleOrNull
import com.bruni.carscan.core.units.DefaultUnitConverter
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.core.units.toUnitId
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The monitoring screen: every signal this vehicle can answer, with running stats, and one of
 * them blown up full-screen with a live plot.
 *
 * This screen has no one-shot events — it navigates nowhere and asks for nothing — so its effect
 * type is [Nothing]. That is a claim the type system checks, rather than an empty sealed interface
 * nobody ever gets round to removing.
 */
class LiveViewModel(
    session: VehicleSessionRepository,
    private val trips: TripRepository,
    settings: SettingsRepository,
    private val vehicle: ActiveVehicle,
    private val poller: VisibleSignals,
    private val bookmarks: BookmarkRepository,
    private val converter: DefaultUnitConverter = DefaultUnitConverter,
    private val capacity: Int = LIVE_PLOT_DEFAULT_CAPACITY,
) : MviViewModel<LiveUiState, LiveIntent, Nothing>(LiveUiState()) {

    /**
     * Every row this vehicle can answer, by key — not just the one on screen.
     *
     * A plain map, deliberately: the sample pump reads it at 20 Hz, and nothing on that path may
     * touch anything Compose observes — apart from each row's own snapshot fields, which
     * invalidate one `Text` and not the list around it.
     */
    private val holders = LinkedHashMap<MetricKey, LiveSeries>()

    /** The one row, if any, whose samples are also going into a plot. Null when the list shows. */
    private var detailKey: MetricKey? = null
    private var detailPlot: LivePlotState? = null

    private val signalset: EffectiveSignalset? get() = vehicle.signalset.value

    init {
        // The only subscriber to the sample stream. Note what is NOT here: nothing derives a new
        // LiveUiState from a sample — even a list of which keys have reported would recompose the
        // whole screen at the sample rate.
        session.samples.collectIntoState(::onSample)

        // The rows come from the *vehicle*, not from what has arrived: a signal is not polled
        // until it is in this list, so a list fed by the sample stream would start empty and stay
        // empty. `unsupported` grows as the car refuses PIDs, and the offer list shrinks with it.
        combine(vehicle.signalset, vehicle.unsupported) { signalset, unsupported ->
            signalset?.seriesOptions(unsupported).orEmpty()
        }.collectIntoState(::onAvailableChanged)

        settings.settings.collectIntoState { current ->
            if (current.units != state.value.units) onUnitsChanged(current.units)
        }

        bookmarks.bookmarks.collectIntoState { starred -> setState { copy(bookmarked = starred) } }
    }

    override fun onIntent(intent: LiveIntent) {
        when (intent) {
            is LiveIntent.Select -> select(intent.key)
            LiveIntent.CloseDetail -> closeDetail()
            is LiveIntent.ToggleBookmark -> scope.launch { bookmarks.toggle(intent.key) }
            LiveIntent.ScreenVisible -> poller.setVisible(holders.keys.toSet())
            is LiveIntent.ShowHistory -> loadHistory(intent.tripId, intent.signalId)
            LiveIntent.HideHistory -> setState { copy(history = null) }
        }
    }

    /**
     * The 20 Hz path. Every row accumulates, whether or not it is the detail — that is the whole
     * point of "poll everything": a min/max/avg the user has not opened yet must still be running
     * when they do. Only the detail signal also costs a ring-buffer push and a conversion; the
     * other ~thirty rows get four comparisons and a `mutableStateOf` write each.
     */
    private fun onSample(sample: SensorSample) {
        val row = holders[sample.key] ?: return
        val native = sample.value.asDoubleOrNull ?: return
        row.record(native)

        if (sample.key == detailKey) {
            val plot = detailPlot ?: return
            val display = convert(native, row.nativeUnit?.toUnitId(), state.value.detail?.displayUnit)
            plot.push(display.toFloat())
        }
    }

    /**
     * Rebuilds the row map to match what the vehicle can answer right now, keeping the holder —
     * and its accumulated stats — for every key that survives, and telling the scheduler to poll
     * the new set. Called on every emission, not only when the set changes: that reactive half is
     * what makes "poll everything" self-correcting when a signal drops in or out. The other half —
     * re-asserting the same set with nothing changed, for when this screen re-enters foreground —
     * is [LiveIntent.ScreenVisible], dispatched by the screen itself.
     */
    private fun onAvailableChanged(options: List<SeriesOption>) {
        val next = LinkedHashMap<MetricKey, LiveSeries>()
        for (option in options) {
            next[option.key] = holders[option.key] ?: newRow(option.key, option.label)
        }
        holders.clear()
        holders.putAll(next)
        poller.setVisible(holders.keys.toSet())

        // The detail signal just stopped being chartable (car quit answering it, most likely).
        if (detailKey != null && detailKey !in holders) closeDetail()

        setState { copy(available = options, rows = holders.values.toList()) }
    }

    /** [SeriesSpec.min]/[SeriesSpec.max] here are native — only a digit-count hint, not a range. */
    private fun newRow(key: MetricKey, label: String): LiveSeries {
        val spec = signalset?.seriesSpec(key)
        val span = (spec?.max ?: 0f) - (spec?.min ?: 0f)
        return LiveSeries(key, label, spec?.unit, decimals = if (span >= 100f) 0 else 1)
    }

    /** A row was tapped, or a deep link arrived with a key already chosen. */
    private fun select(key: MetricKey) {
        val spec = signalset?.seriesSpec(key) ?: return
        val row = holders[key] ?: return
        detailKey = key
        detailPlot = LivePlotState(capacity)
        setState { copy(detail = buildDetail(row, spec, state.value.units)) }
    }

    private fun closeDetail() {
        detailKey = null
        detailPlot = null
        setState { copy(detail = null) }
    }

    /**
     * The range is converted with the samples, and by the same rule.
     *
     * `convert`, not `convertDelta`: `min` and `max` are absolute readings, and a coolant range of
     * 0–120 °C is 32–248 °F, not 0–216. (A *span* would be the other function — keeping those two
     * apart is why `:core:units` has both.)
     */
    private fun buildDetail(row: LiveSeries, spec: SeriesSpec, units: UnitPreferences): DetailUiState {
        val native = spec.unit?.toUnitId()
        val display = native?.let { units[it.quantity] }

        val min = convert(spec.min.toDouble(), native, display)
        val max = convert(spec.max.toDouble(), native, display)

        // An inverse unit maps zero to infinity: 0 kWh/100km is *infinite* miles per kWh, and no
        // scale has that on it. A range that does not survive its own conversion is drawn as the
        // car reported it, which is at least a number.
        val convertible = min.isFinite() && max.isFinite()

        return DetailUiState(
            series = row,
            plot = detailPlot!!,
            min = (if (convertible) minOf(min, max) else spec.min.toDouble()).toFloat(),
            max = (if (convertible) maxOf(min, max) else spec.max.toDouble()).toFloat(),
            displayUnit = if (convertible) display else native,
        )
    }

    /**
     * The open detail's window holds values in the old unit and a ring buffer cannot be read back
     * out, so it is rebuilt empty. Thirty seconds of trace is the honest price of never drawing °F
     * against a °C scale. Rows are untouched: their stats are native and read nothing here.
     */
    private fun onUnitsChanged(units: UnitPreferences) {
        val key = detailKey
        val spec = key?.let { signalset?.seriesSpec(it) }
        val row = key?.let { holders[it] }

        if (spec != null && row != null) {
            detailPlot = LivePlotState(capacity)
            setState { copy(units = units, detail = buildDetail(row, spec, units)) }
        } else {
            setState { copy(units = units) }
        }
    }

    /** Off the 1 Hz columnar rollup. Samples above 1 Hz are never persisted and do not exist. */
    private fun loadHistory(tripId: String, signalId: String) {
        scope.launch {
            val stored = trips.series(tripId, signalId) ?: return@launch
            val spec = signalset?.seriesSpec(MetricKey.Signal(signalId))
            val native = spec?.unit?.toUnitId()
            val display = native?.let { state.value.units[it.quantity] }

            val segments = stored.toSegments().map { segment ->
                segment.map { point ->
                    point.copy(value = convert(point.value.toDouble(), native, display).toFloat())
                }
            }
            setState {
                copy(history = HistoryUiState(tripId, spec?.label ?: signalId, display, segments))
            }
        }
    }

    /** A signal with no display unit — rpm, percent, volts — is shown exactly as decoded. */
    private fun convert(value: Double, from: UnitId?, to: UnitId?): Double =
        if (from == null || to == null || from == to) value else converter.convert(value, from, to)
}
