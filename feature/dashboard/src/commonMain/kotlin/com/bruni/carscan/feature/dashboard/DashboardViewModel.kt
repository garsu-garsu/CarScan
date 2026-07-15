package com.bruni.carscan.feature.dashboard

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.data.ActiveVehicle
import com.bruni.carscan.core.data.DashboardLayout
import com.bruni.carscan.core.data.DashboardLayoutRepository
import com.bruni.carscan.core.data.SessionHealth
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.VehicleSessionRepository
import com.bruni.carscan.core.data.VisibleSignals
import com.bruni.carscan.core.data.newUuid
import com.bruni.carscan.core.designsystem.gauge.GaugeSpec
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

data class TileState(
    val id: String,
    val key: MetricKey,
    val style: GaugeStyleId,
    val spec: GaugeSpec,
    /** What the car reports this signal in. */
    val nativeUnit: ObdUnit? = null,
    /**
     * What [spec] has been converted to, or null when the app offers no choice of unit for this
     * quantity. The screen turns this into [GaugeSpec.unitLabel]: only a composable can resolve a
     * localized symbol, and the mapper's job is to decide the *unit*, not to spell it.
     */
    val displayUnit: UnitId? = null,
)

data class DashboardState(
    val tiles: List<TileState> = emptyList(),
    /** What the picker may offer — this car's signalset, minus what it cannot answer. */
    val available: List<PickerEntry> = emptyList(),
    val health: SessionHealth = SessionHealth(),
    val editing: Boolean = false,
    val pickerOpen: Boolean = false,
)

sealed interface DashboardIntent {
    data class Add(val key: MetricKey) : DashboardIntent
    data class Remove(val id: String) : DashboardIntent
    data class Move(val from: Int, val to: Int) : DashboardIntent
    data class SetStyle(val id: String, val style: GaugeStyleId) : DashboardIntent

    /**
     * The tiles the grid is currently showing, emitted by the screen as the user scrolls.
     *
     * This is what buys the visible gauges the adapter's budget — see [VisibleSignals].
     */
    data class VisibleTiles(val ids: List<String>) : DashboardIntent

    data class TileTapped(val id: String) : DashboardIntent
    data object OpenPicker : DashboardIntent
    data object ClosePicker : DashboardIntent
    data object ToggleEditing : DashboardIntent

    /** Enter the windshield HUD. Full-screen driving mode — see [DashboardEffect.OpenHud]. */
    data object OpenHud : DashboardIntent
}

sealed interface DashboardEffect {
    /** Features never depend on each other: the screen asks, and `:composeApp` navigates. */
    data class OpenLiveChart(val key: MetricKey) : DashboardEffect
    data object OpenHud : DashboardEffect
}

class DashboardViewModel(
    private val session: VehicleSessionRepository,
    private val layouts: DashboardLayoutRepository,
    private val settings: SettingsRepository,
    private val vehicle: ActiveVehicle,
    private val visibility: VisibleSignals,
    private val clock: DashboardClock,
    private val ticks: Flow<Long> = tickerFlow(clock),
) : MviViewModel<DashboardState, DashboardIntent, DashboardEffect>(DashboardState()) {

    /** The user's layout. The single source of truth for what is on the dashboard. */
    private val tiles = MutableStateFlow<List<DashboardTile>>(emptyList())

    /** What the grid says is on screen right now. */
    private val onScreen = MutableStateFlow<List<String>>(emptyList())

    private var layout: DashboardLayout? = null

    init {
        scope.launch { restore() }
        scope.launch { render() }
        scope.launch { announceVisibility() }
        scope.launch { session.health.collect { health -> setState { copy(health = health) } } }
        scope.launch {
            combine(vehicle.signalset, vehicle.unsupported, ::availableTiles)
                .collect { offers -> setState { copy(available = offers) } }
        }
    }

    override fun onIntent(intent: DashboardIntent) {
        when (intent) {
            is DashboardIntent.Add -> add(intent.key)

            is DashboardIntent.Remove -> edit { filterNot { it.id == intent.id } }

            is DashboardIntent.Move -> edit { move(intent.from, intent.to) }

            is DashboardIntent.SetStyle -> edit {
                map { if (it.id == intent.id) it.copy(style = intent.style) else it }
            }

            is DashboardIntent.VisibleTiles -> onScreen.value = intent.ids

            is DashboardIntent.TileTapped ->
                tiles.value.firstOrNull { it.id == intent.id }
                    ?.let { emitEffect(DashboardEffect.OpenLiveChart(it.key)) }

            DashboardIntent.OpenPicker -> setState { copy(pickerOpen = true) }
            DashboardIntent.ClosePicker -> setState { copy(pickerOpen = false) }
            DashboardIntent.ToggleEditing -> setState { copy(editing = !editing) }
            DashboardIntent.OpenHud -> emitEffect(DashboardEffect.OpenHud)
        }
    }

    // --- what the user is looking at ------------------------------------------

    /**
     * Tells the poller which keys are on screen, and tells it again whenever that changes —
     * whether because the user scrolled or because a tile was added or removed.
     *
     * [distinctUntilChanged] because a `LazyGrid` re-reports its visible items on every scroll
     * frame, and the scheduler would otherwise be handed the same set sixty times a second.
     */
    private suspend fun announceVisibility() {
        combine(tiles, onScreen) { all, ids ->
            all.filter { it.id in ids }.map { it.key }.toSet()
        }
            .distinctUntilChanged()
            .collect(visibility::setVisible)
    }

    // --- the state mapper -----------------------------------------------------

    /**
     * The one place a sample becomes something a user reads: bound to the signalset, converted to
     * the user's units, and aged.
     *
     * [ticks] is here because a tile does not go stale when something happens — it goes stale when
     * *nothing* does. No sample arrives to trigger a recomposition, so the passage of time has to
     * be a flow in its own right. It is [onStart]ed so that the grid draws the moment it has
     * tiles: a `combine` that waited for the first tick would leave the dashboard blank for half
     * a second on every open, and a tick is a heartbeat, not a precondition.
     */
    private suspend fun render() {
        combine(
            tiles,
            session.latest,
            vehicle.signalset,
            settings.settings,
            ticks.onStart { emit(clock.epochMs()) },
        ) { all, latest, signalset, prefs, _ ->
            renderTiles(all, latest, signalset, prefs.units)
        }.collect { rendered -> setState { copy(tiles = rendered) } }
    }

    private fun renderTiles(
        all: List<DashboardTile>,
        latest: Map<MetricKey, SensorSample>,
        signalset: EffectiveSignalset?,
        units: UnitPreferences,
    ): List<TileState> {
        val now = clock.epochMs()
        return all.map { tile ->
            val rendered = resolve(
                tile = tile,
                binding = signalset?.get(tile.key)?.firstOrNull()?.let(::bind),
                sample = latest[tile.key],
                nowMs = now,
                units = units,
            )
            TileState(
                id = tile.id,
                key = tile.key,
                style = tile.style,
                spec = rendered.spec,
                nativeUnit = rendered.nativeUnit,
                displayUnit = rendered.displayUnit,
            )
        }
    }

    // --- editing the layout ---------------------------------------------------

    private fun add(key: MetricKey) {
        val offer = state.value.available.firstOrNull { it.key == key }
        edit {
            this + DashboardTile(
                id = newUuid(),
                key = key,
                style = DEFAULT_STYLE,
                // Native units, always — see DashboardTile. A range saved in mph would pin a
                // 240 km/h car at the end stop the moment the user switched back to metric.
                min = offer?.defaultMin ?: 0.0,
                max = offer?.defaultMax ?: FALLBACK_MAX,
            )
        }
        setState { copy(pickerOpen = false) }
    }

    private fun edit(change: List<DashboardTile>.() -> List<DashboardTile>) {
        tiles.value = tiles.value.change()
        scope.launch { persist() }
    }

    private suspend fun persist() {
        val current = layout ?: return
        val updated = current.copy(layoutJson = LayoutCodec.encode(tiles.value))
        layout = updated
        layouts.save(updated)
    }

    /**
     * A layout keyed on no vehicle at all is not an edge case: before anything is paired there is
     * no vehicle to key one on, and the app still has to show the user a dashboard.
     */
    private suspend fun restore() {
        val vehicleId = settings.settings.first().activeVehicleId
        layout = vehicleId?.let { layouts.activeFor(it) } ?: DashboardLayout(
            id = newUuid(),
            vehicleId = vehicleId,
            name = DEFAULT_LAYOUT_NAME,
            isActive = true,
            layoutJson = LayoutCodec.encode(emptyList()),
        )
        tiles.value = LayoutCodec.decode(layout!!.layoutJson)
    }

    private companion object {
        val DEFAULT_STYLE = GaugeStyleId.MODERN_ARC
        const val FALLBACK_MAX = 100.0

        /** Never shown in M5 — there is no multi-layout UI yet. See the report. */
        const val DEFAULT_LAYOUT_NAME = "Dashboard"
    }
}

/** Reorders in place, the way a drag-and-drop grid does. Out-of-range indices leave it alone. */
internal fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
