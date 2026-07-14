package com.bruni.carscan.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.designsystem.gauge.GaugeTheme
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Colour choices are taste, but *legibility* is not — it is arithmetic, and a driver reading a
 * gauge at 100 km/h is the worst-case viewer there is. So the tokens are asserted on WCAG
 * relative luminance and contrast ratio rather than on their hex values, which would only
 * assert that somebody typed what they typed.
 */
class GaugeThemeTokensTest {

    private val themes = listOf(
        "modern dark" to GaugeThemes.ModernDark,
        "classic" to GaugeThemes.Classic,
        "hud" to GaugeThemes.Hud,
    )

    // --- structure ----------------------------------------------------------

    @Test
    fun theStyleIdSelectsItsGaugeTheme() {
        assertEquals(GaugeThemes.ModernDark, GaugeThemes.forStyle(GaugeStyleId.MODERN_ARC))
        assertEquals(GaugeThemes.Classic, GaugeThemes.forStyle(GaugeStyleId.CLASSIC_ANALOG))
    }

    @Test
    fun everyGaugeColourIsFullyOpaque() {
        // A translucent needle over the HUD's pure black is a needle nobody can see, and
        // alpha is exactly the kind of thing that gets copied in from a mockup by accident.
        for ((name, theme) in themes) {
            for ((role, color) in theme.roles()) {
                assertEquals(1f, color.alpha, "$name.$role is translucent")
            }
        }
    }

    @Test
    fun redlineIsNeverConfusableWithTheValueOrTheOptimalBand() {
        // These three carry opposite meanings — "fine", "good", "you are damaging the engine".
        for ((name, theme) in themes) {
            assertNotEquals(theme.value, theme.redline, "$name: redline reads as a normal value")
            assertNotEquals(theme.optimal, theme.redline, "$name: redline reads as the optimal band")
        }
    }

    @Test
    fun theNoReadingColourIsAlwaysLessProminentThanALiveValue() {
        // isStale exists so a gauge that never got a reading cannot be mistaken for one showing
        // zero. If the stale colour shouted as loudly as the value colour it would defeat that.
        for ((name, theme) in themes) {
            val live = contrast(theme.value, theme.track)
            val stale = contrast(theme.stale, theme.track)
            assertTrue(stale < live, "$name: stale ($stale) is as loud as a live value ($live)")
        }
    }

    // --- the three looks ----------------------------------------------------

    @Test
    fun modernDarkIsAnEvClusterNearBlackGroundNeonAccentHighContrastNumerals() {
        val t = GaugeThemes.ModernDark
        assertTrue(luminance(t.track) < 0.05f, "the unfilled arc is not a near-black ground")
        assertTrue(luminance(t.value) > 0.4f, "the accent is not neon")
        assertTrue(contrast(t.text, t.track) >= 7f, "the numerals are not high-contrast")
    }

    @Test
    fun classicIsADarkNeedleOnALightDialFace() {
        val t = GaugeThemes.Classic
        assertTrue(luminance(t.track) > 0.5f, "the dial face is not light")
        assertTrue(luminance(t.needle) < 0.1f, "the needle is not dark")
        assertTrue(contrast(t.needle, t.track) >= 7f, "the needle does not stand off the face")
        assertTrue(contrast(t.text, t.track) >= 7f, "the numerals do not stand off the face")
    }

    @Test
    fun theHudIsBrighterThanAnythingThatLooksRightOnADesk() {
        // A HUD is read as a reflection in a windscreen, which throws most of the luminance
        // away. Tuned by eye on a monitor it is unreadable in the car — so it is pinned here
        // to being *strictly* louder than the theme that was tuned on a monitor.
        val hud = GaugeThemes.Hud
        val desk = GaugeThemes.ModernDark

        assertTrue(
            contrast(hud.value, Color.Black) > contrast(desk.value, Color.Black),
            "the HUD accent is no brighter than the desk theme's",
        )
        assertTrue(
            contrast(hud.text, Color.Black) > contrast(desk.text, Color.Black),
            "the HUD numerals are no brighter than the desk theme's",
        )
    }

    @Test
    fun everyHudColourThatCarriesAReadingSurvivesAWindscreen() {
        val hud = GaugeThemes.Hud

        // The numerals, the needle and the value arc are what the driver reads.
        for (role in listOf("value", "needle", "text")) {
            val color = hud.roles().toMap().getValue(role)
            assertTrue(
                contrast(color, Color.Black) >= 9f,
                "hud.$role is ${contrast(color, Color.Black)}:1 on black — too dim to reflect",
            )
        }

        // The bands are large areas, not text, and the redline has to stay *red* — a driver must
        // not have to learn a second colour language for the HUD. Red is intrinsically dim (pure
        // red is 5.25:1 on black at best), so it is held to a large-area bar instead.
        for (role in listOf("optimal", "redline")) {
            val color = hud.roles().toMap().getValue(role)
            assertTrue(
                contrast(color, Color.Black) >= 4.5f,
                "hud.$role is ${contrast(color, Color.Black)}:1 on black — invisible as a band",
            )
        }
    }

    private fun GaugeTheme.roles() = listOf(
        "track" to track,
        "value" to value,
        "needle" to needle,
        "text" to text,
        "optimal" to optimal,
        "redline" to redline,
        "stale" to stale,
    )

    /** WCAG 2.1 relative luminance. */
    private fun luminance(color: Color): Float {
        fun channel(c: Float) =
            if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * channel(color.red) +
            0.7152f * channel(color.green) +
            0.0722f * channel(color.blue)
    }

    /** WCAG 2.1 contrast ratio, 1:1 (identical) to 21:1 (black on white). */
    private fun contrast(a: Color, b: Color): Float {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
