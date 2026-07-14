package com.bruni.carscan.feature.dashboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import kotlin.math.max
import kotlin.math.min

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

    GaugeStyleId.MODERN_ARC -> surface
}

/** The dial face of a classic instrument. Light, always — see [tileFace]. */
val CLASSIC_FACE: Color = Color(0xFFF7F4EC)

/**
 * WCAG contrast between two opaque colours, `1.0` (identical) to `21.0` (black on white).
 *
 * Here to make "the needle is invisible" an assertion rather than an opinion. A gauge nobody can
 * read is a bug that renders perfectly and passes every test we would otherwise write about it.
 */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance() + 0.05
    val lb = b.luminance() + 0.05
    return (max(la, lb) / min(la, lb)).toDouble()
}

/** WCAG AA for large text and graphical objects. A needle below this cannot be read at a glance. */
const val MIN_LEGIBLE_CONTRAST: Double = 3.0
