package com.bruni.carscan.feature.hud

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.data.ActiveVehicle
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.VehicleSessionRepository
import com.bruni.carscan.core.data.VisibleSignals
import com.bruni.carscan.core.designsystem.gauge.GaugeSpec
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.asDoubleOrNull
import com.bruni.carscan.core.units.DefaultUnitConverter
import com.bruni.carscan.core.units.UnitPreferences
import com.bruni.carscan.core.units.displayUnitFor
import com.bruni.carscan.core.units.toUnitId
import com.bruni.carscan.core.vehicle.EffectiveSignalset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Below this, jitter in a fast poll would flicker a reading in and out of staleness. Flat, per the brief. */
const val STALE_WINDOW_MS: Long = 3_000

/** A reading with no signalset entry yet draws on this range rather than a zero-width dial. */
private const val FALLBACK_MAX = 100.0

/**
 * The windshield HUD's whole state machine: a fixed pair of gauges (speed, RPM), converted to the
 * user's units and aged against the wall clock — the read-only sibling of `feature/dashboard`'s
 * `DashboardViewModel`.
 */
class HudViewModel(
    private val session: VehicleSessionRepository,
    private val vehicle: ActiveVehicle,
    private val settings: SettingsRepository,
    private val visibility: VisibleSignals,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val ticks: Flow<Long> = tickerFlow(now),
) : MviViewModel<HudState, HudIntent, HudEffect>(HudState()) {

    init {
        // Without this the poller starves these two gauges on a counterfeit ELM327's ~15
        // queries/sec budget — see VisibleSignals.
        visibility.setVisible(HUD_FIXED_KEYS.toSet())
        scope.launch { render() }
    }

    override fun onIntent(intent: HudIntent) {
        // Nothing to reduce — the HUD only displays.
    }

    /**
     * [ticks] is [onStart]ed so the HUD draws the moment it has a signalset, rather than waiting
     * half a second for the first tick — see `DashboardViewModel.render` for the same reasoning.
     */
    private suspend fun render() {
        combine(
            session.latest,
            vehicle.signalset,
            settings.settings,
            ticks.onStart { emit(now()) },
        ) { latest, signalset, prefs, _ ->
            val nowMs = now()
            HudState(
                readings = HUD_FIXED_KEYS.map { key ->
                    buildReading(key, latest[key], signalset, prefs.units, nowMs)
                },
            )
        }.collect { rendered -> setState { rendered } }
    }

    private fun buildReading(
        key: MetricKey,
        sample: SensorSample?,
        signalset: EffectiveSignalset?,
        units: UnitPreferences,
        nowMs: Long,
    ): HudReading {
        val source = signalset?.get(key)?.firstOrNull()
        val fmt = source?.signal?.fmt
        val native: ObdUnit? = fmt?.unit
        val nativeId = native?.toUnitId()
        val displayId = native?.let { displayUnitFor(it, units) }

        // A unit the app offers no choice in — rpm, volts — has no UnitId, and is shown exactly
        // as decoded. See TileMapper.resolve for the fuller version of this reasoning.
        fun Double.display(): Double =
            if (nativeId != null && displayId != null) {
                DefaultUnitConverter.convert(this, nativeId, displayId)
            } else {
                this
            }

        val min = (fmt?.min ?: 0.0).display()
        val max = (fmt?.max ?: FALLBACK_MAX).display()
        val reading = sample?.value?.asDoubleOrNull

        return HudReading(
            key = key,
            spec = GaugeSpec(
                label = source?.signal?.name ?: key.fallbackLabel(),
                value = (reading?.display() ?: 0.0).toFloat(),
                min = min.toFloat(),
                max = max.toFloat(),
                // Filled in by the screen: only a composable can resolve a localized symbol.
                unitLabel = "",
                decimals = 0,
                optimalFrom = fmt?.omin?.display()?.toFloat(),
                optimalTo = fmt?.omax?.display()?.toFloat(),
                redlineFrom = fmt?.oval?.display()?.toFloat(),
                isStale = sample == null || reading == null || nowMs - sample.timestampMs > STALE_WINDOW_MS,
            ),
            nativeUnit = native,
            displayUnit = displayId,
        )
    }
}

private fun MetricKey.fallbackLabel(): String = when (this) {
    is MetricKey.Signal -> signalId
    is MetricKey.Metric -> metric.name
}

/**
 * A reading does not go stale because something happened — it goes stale because *nothing* did.
 * No sample arrives to trigger a re-render, so the passage of time has to be a flow of its own.
 * Written locally rather than importing `feature/dashboard`'s ticker: features never depend on
 * each other.
 */
internal fun tickerFlow(now: () -> Long, periodMs: Long = HUD_TICK_MS): Flow<Long> = flow {
    while (true) {
        emit(now())
        delay(periodMs)
    }
}

/** Fine enough that a reading dims promptly, coarse enough not to re-render the HUD for nothing. */
internal const val HUD_TICK_MS: Long = 500
