package com.bruni.carscan.feature.dashboard

import androidx.compose.ui.graphics.Color
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId

/**
 * The dial face a tile paints **behind** its gauge, and the reason it is not just the Material
 * surface.
 *
 * `GaugeTheme` deliberately carries no background colour: that is what lets one renderer serve
 * both a dashboard tile and the HUD, which is pure black and not a Material surface at all. So a
 * renderer fills nothing — `ClassicAnalogGauge` strokes a rim, ticks and a needle and leaves the
 * middle transparent. Whatever is behind the tile *is* the dial face.
 *
 * Which means the classic gauge's near-black needle and numerals, dropped on the dark surface this
 * app uses by default, are **a black needle on a black ground.** It renders without error, every
 * assertion about its geometry passes, and the user sees an empty circle. So the tile — not the
 * theme, and not the renderer — has to supply a light face for [GaugeStyleId.CLASSIC_ANALOG].
 *
 * The modern arc is the opposite: it is a neon accent designed for a dark ground, and it gets the
 * surface unchanged.
 */
fun tileFace(style: GaugeStyleId, surface: Color): Color = when (style) {
    // A real instrument dial: cream, and deliberately a shade lighter than the parchment rim
    // GaugeThemes.Classic strokes at 0xFFEDE7DA, so the rim still reads against it.
    GaugeStyleId.CLASSIC_ANALOG -> CLASSIC_FACE

    // The other four shapes are all dark-ground moderns like the arc — GaugeThemes.forStyle
    // gives them the ModernDark palette, so they get the Material surface unchanged too.
    GaugeStyleId.MODERN_ARC,
    GaugeStyleId.SEMICIRCLE,
    GaugeStyleId.NUMERIC,
    GaugeStyleId.LINEAR_BAR_H,
    GaugeStyleId.LINEAR_BAR_V,
    -> surface
}

/** The dial face of a classic instrument. Light, always — see [tileFace]. */
val CLASSIC_FACE: Color = Color(0xFFF7F4EC)
