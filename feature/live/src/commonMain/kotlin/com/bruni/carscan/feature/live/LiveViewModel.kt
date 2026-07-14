package com.bruni.carscan.feature.live

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.data.ActiveVehicle
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
import com.bruni.carscan.core.units.UnitConverter
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.core.units.toUnitId
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The live screen: a rolling window of what the car is doing now, and a stored trip's trace.
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
    private val converter: UnitConverter = DefaultUnitConverter,
    private val capacity: Int = LIVE_PLOT_DEFAULT_CAPACITY,
) : MviViewModel<LiveUiState, LiveIntent, Nothing>(LiveUiState()) {

    /**
     * The charted series, by key.
     *
     * A plain map, deliberately: the sample pump reads it at 20 Hz, and nothing on that path may
     * touch anything Compose observes — apart from each plot's own revision counter, which
     * invalidates a draw and not a composition.
     */
    private val plots = LinkedHashMap<MetricKey, LiveSeries>()

    private val signalset: EffectiveSignalset? get() = vehicle.signalset.value

    init {
        // The only subscriber to the sample stream. Note what is NOT here: nothing derives state
        // from `session.latest`. A state that changed when a sample arrived — even a list of which
        // keys have reported — would recompose the whole screen at the sample rate.
        session.samples.collectIntoState(::onSample)

        // The picker comes from the *vehicle*, not from what has arrived: a signal is not polled
        // until it is charted, so a picker fed by the sample stream would start empty and stay
        // empty. `unsupported` grows as the car refuses PIDs, and the offer list shrinks with it.
        combine(vehicle.signalset, vehicle.unsupported) { signalset, unsupported ->
            signalset?.seriesOptions(unsupported).orEmpty()
        }.collectIntoState { options ->
            if (options != state.value.available) setState { copy(available = options) }
        }

        settings.settings.collectIntoState { current ->
            if (current.units != state.value.units) onUnitsChanged(current.units)
        }
    }

    override fun onIntent(intent: LiveIntent) {
        when (intent) {
            is LiveIntent.ToggleSeries -> toggle(intent.key)
            is LiveIntent.ShowHistory -> loadHistory(intent.tripId, intent.signalId)
            LiveIntent.HideHistory -> setState { copy(history = null) }
        }
    }

    /**
     * The 20 Hz path. It pushes into a preallocated ring buffer and writes one scalar, and it does
     * not produce a new [LiveUiState] — that would recompose the screen, and the chart with it,
     * once per sample.
     */
    private fun onSample(sample: SensorSample) {
        val series = plots[sample.key] ?: return
        val native = sample.value.asDoubleOrNull ?: return

        // The plot's ring buffer holds *converted* values, drawn against a converted range. That
        // is arithmetic, not display — no number is turned into text here, at 20 Hz.
        val display = convert(native, series.nativeUnit?.toUnitId(), series.displayUnit)
        series.plot.push(display.toFloat())

        // The readout keeps the value the car reported. `UnitReadout` converts and formats it in
        // one call at the moment it is drawn, which is the only way to put a converted number on
        // screen — see LiveSeries.latest. The sample itself is untouched either way: it reaches
        // the trip database in the unit OBDb declared it in.
        series.latest = native
    }

    private fun toggle(key: MetricKey) {
        if (plots.remove(key) == null) {
            val spec = signalset?.seriesSpec(key) ?: return   // not chartable on this vehicle
            plots[key] = newSeries(key, spec, state.value.units)
        }
        setState { copy(charted = plots.values.toList()) }

        // What is on screen is what the scheduler should spend its fifteen queries a second on.
        // Called on both edges, not just on select: a deselected series must stop costing a query.
        poller.setVisible(plots.keys.toSet())
    }

    /**
     * The windows hold values in the old unit and a ring buffer cannot be read back out, so the
     * series are rebuilt empty. Thirty seconds of trace is the honest price of never drawing °F
     * against a °C scale.
     */
    private fun onUnitsChanged(units: UnitPreferences) {
        val keys = plots.keys.toList()
        plots.clear()
        for (key in keys) {
            val spec = signalset?.seriesSpec(key) ?: continue
            plots[key] = newSeries(key, spec, units)
        }
        setState { copy(units = units, charted = plots.values.toList()) }
    }

    /**
     * The range is converted with the samples, and by the same rule.
     *
     * `convert`, not `convertDelta`: `min` and `max` are absolute readings, and a coolant range of
     * 0–120 °C is 32–248 °F, not 0–216. (A *span* would be the other function — keeping those two
     * apart is why `:core:units` has both.)
     */
    private fun newSeries(key: MetricKey, spec: SeriesSpec, units: UnitPreferences): LiveSeries {
        val native = spec.unit?.toUnitId()
        val display = native?.let { units[it.quantity] }

        val min = convert(spec.min.toDouble(), native, display)
        val max = convert(spec.max.toDouble(), native, display)

        // An inverse unit maps zero to infinity: 0 kWh/100km is *infinite* miles per kWh, and no
        // scale has that on it. A series whose range does not survive its own conversion is drawn
        // as the car reported it, which is at least a number.
        val convertible = min.isFinite() && max.isFinite()

        return LiveSeries(
            key = key,
            label = spec.label,
            min = (if (convertible) minOf(min, max) else spec.min.toDouble()).toFloat(),
            max = (if (convertible) maxOf(min, max) else spec.max.toDouble()).toFloat(),
            nativeUnit = spec.unit,
            displayUnit = if (convertible) display else native,
            plot = LivePlotState(capacity),
        )
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
