package com.bruni.carscan.core.designsystem.gauge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Everything a gauge needs to draw itself, and nothing about where the number came from.
 *
 * Deliberately free of OBD concepts: no signal id, no PID, no unit enum. [value] has already
 * been converted to the user's preferred unit and [unitLabel] is already localized. A gauge
 * that knew about `SuggestedMetric` would have to know about unit conversion, and then the
 * conversion would happen in two places — which is how an app ends up showing km/h on one
 * screen and mph on another.
 */
@Immutable
data class GaugeSpec(
    val label: String,
    val value: Float,
    val min: Float,
    val max: Float,
    /** Already localized, e.g. "km/h", "°C", "rpm". */
    val unitLabel: String,
    val decimals: Int = 0,
    /**
     * The "healthy" band, from OBDb's `fmt.omin`/`omax`. Drawn as a green zone.
     * These are display hints and never affect decoding.
     */
    val optimalFrom: Float? = null,
    val optimalTo: Float? = null,
    /** Above this, the gauge goes red. From `fmt.oval`, or a per-metric default. */
    val redlineFrom: Float? = null,
    /**
     * True when there is no live reading — the adapter has not answered yet, the ECU does not
     * support this signal, or a `nullmin`/`nullmax` sentinel fired.
     *
     * A gauge MUST distinguish this from zero. A tachometer resting at 0 rpm and a tachometer
     * that has never received a value look identical if this is ignored, and users read the
     * first as "engine off" rather than "app is broken".
     */
    val isStale: Boolean = false,
)

/** Which renderer draws a tile. Persisted per-tile in the dashboard layout. */
enum class GaugeStyleId {
    MODERN_ARC,
    CLASSIC_ANALOG,
    /** A 180° half-circle arc, top half only. Value sweeps left→right. */
    SEMICIRCLE,
    /** No dial graphic at all — just the big centered number the [Overlay] already draws. */
    NUMERIC,
    /** A horizontal fill bar: track plus a proportional filled portion. */
    LINEAR_BAR_H,
    /** A vertical fill bar, filling bottom→top. */
    LINEAR_BAR_V,
}

/**
 * Colors a renderer may use. Passed in rather than read from MaterialTheme so that a gauge
 * renders identically inside the HUD, which has its own high-contrast palette and is not a
 * Material surface at all.
 */
@Immutable
data class GaugeTheme(
    val track: Color,
    val value: Color,
    val needle: Color,
    val text: Color,
    val optimal: Color,
    val redline: Color,
    val stale: Color,
)

/**
 * A gauge style.
 *
 * Two implementations ship: a minimal arc for the modern dark theme, and a needle-and-ticks
 * dial for the classic one. The style is a runtime setting, so this is an interface rather
 * than two composables — the dashboard does not know which one it is drawing.
 *
 * [draw] is the whole gauge except its numerals. Text goes in [Overlay] as real `Text`, not
 * `drawText`, so it picks up font scaling, the app's locale, and non-Latin scripts (Russian,
 * Korean) without the renderer having to think about any of it.
 *
 * [animatedValue] is pre-smoothed by the caller. A renderer must never animate internally:
 * at 20 Hz the animation would never settle, and every renderer would have to solve the same
 * problem differently.
 */
interface GaugeRenderer {
    val id: GaugeStyleId

    fun DrawScope.draw(spec: GaugeSpec, theme: GaugeTheme, animatedValue: Float)

    @Composable
    fun Overlay(spec: GaugeSpec, theme: GaugeTheme) {
    }
}

/** The renderer the user picked. Set once, near the root, from settings. */
val LocalGaugeRenderer = staticCompositionLocalOf<GaugeRenderer> {
    error("No GaugeRenderer provided — wrap the content in a CarScanTheme")
}
