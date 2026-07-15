package com.bruni.carscan.feature.hud

import com.bruni.carscan.core.designsystem.gauge.GaugeSpec
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.units.UnitId

/** The HUD's fixed gauge set, in display order: speed first (emphasized), then RPM. */
val HUD_SPEED_KEY: MetricKey = MetricKey.Metric(SuggestedMetric.SPEED)
val HUD_RPM_KEY: MetricKey = MetricKey.Signal("RPM")
val HUD_FIXED_KEYS: List<MetricKey> = listOf(HUD_SPEED_KEY, HUD_RPM_KEY)

/**
 * One gauge's reading, ready to draw.
 *
 * [nativeUnit] and [displayUnit] ride along the same way `feature/dashboard`'s `TileState` carries
 * them: only a composable can resolve a localized unit symbol, so the ViewModel decides *which*
 * unit a reading is in and the screen decides how to say it — see `HudScreen`'s `hudUnitLabel`.
 */
data class HudReading(
    val key: MetricKey,
    val spec: GaugeSpec,
    /** What the car reports this signal in. */
    val nativeUnit: ObdUnit? = null,
    /** What [spec] has been converted to, or null when the app offers no choice for this quantity. */
    val displayUnit: UnitId? = null,
)

data class HudState(val readings: List<HudReading> = emptyList())

/** The HUD is read-only — there is nothing for the user to do on it. */
sealed interface HudIntent

sealed interface HudEffect
