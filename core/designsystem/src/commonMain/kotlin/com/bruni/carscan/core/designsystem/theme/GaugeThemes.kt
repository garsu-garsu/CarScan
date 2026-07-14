package com.bruni.carscan.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.designsystem.gauge.GaugeTheme

/**
 * The three palettes a gauge can be drawn in.
 *
 * A [GaugeTheme] carries no background colour, deliberately: the same renderer has to work on a
 * Material surface *and* inside the HUD, which is not a surface at all. So every colour here is
 * chosen against the ground it will actually be seen on, and `GaugeThemeTokensTest` pins that
 * down as contrast ratios rather than as hex values.
 */
object GaugeThemes {

    /**
     * The EV-cluster look: a near-black ground, one neon accent, numerals bright enough to read
     * at a glance without looking at them.
     */
    val ModernDark: GaugeTheme = GaugeTheme(
        track = Color(0xFF14171B),
        value = Color(0xFF00E5FF),
        needle = Color(0xFF00E5FF),
        text = Color(0xFFF2F5F7),
        optimal = Color(0xFF00E676),
        redline = Color(0xFFFF3B30),
        stale = Color(0xFF565D66),
    )

    /**
     * The instrument-dial look: a light dial face, a near-black needle and numerals on it, and a
     * red redline zone.
     *
     * Note it is *light*. `ClassicAnalogGauge` strokes a rim and a needle and fills nothing, so
     * the dial face is whatever is behind the tile — which means a classic gauge must be given a
     * light tile, not a dark one, whatever the app's Material scheme is doing. See the note on
     * [CarScanTheme].
     */
    val Classic: GaugeTheme = GaugeTheme(
        track = Color(0xFFEDE7DA),
        value = Color(0xFF2B2F33),
        needle = Color(0xFF16181C),
        text = Color(0xFF16181C),
        optimal = Color(0xFF2E7D32),
        redline = Color(0xFFC62828),
        stale = Color(0xFFB0AA9E),
    )

    /**
     * Pure black, one high-luminance amber hue.
     *
     * A HUD is not looked at — it is a reflection off a windscreen, and the windscreen throws
     * away most of the light before it reaches the driver. Anything tuned to look right on a
     * monitor is unreadable in a car, so this is deliberately louder than the modern theme:
     * every colour that carries a reading clears 9:1 against black, and the numerals are close
     * to full white. It looks garish on a desk. That is the correct outcome.
     *
     * The optimal band stays green and the redline stays red — a driver must not have to learn
     * a new colour language for the HUD — but they are bands, not numerals, so they are held to
     * a large-area contrast bar rather than a text one.
     */
    val Hud: GaugeTheme = GaugeTheme(
        track = Color(0xFF3A2E05),
        value = Color(0xFFFFD740),
        needle = Color(0xFFFFD740),
        text = Color(0xFFFFFBEA),
        optimal = Color(0xFF3DDC84),
        redline = Color(0xFFFF3B30),
        stale = Color(0xFF6B6152),
    )

    /** The palette that goes with a gauge style. The HUD picks [Hud] itself; it is not a style. */
    fun forStyle(style: GaugeStyleId): GaugeTheme = when (style) {
        GaugeStyleId.MODERN_ARC -> ModernDark
        GaugeStyleId.CLASSIC_ANALOG -> Classic
    }
}
