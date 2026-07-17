package com.bruni.carscan.feature.dashboard

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.designsystem.theme.GaugeThemes
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test

/**
 * WCAG contrast between two opaque colours, `1.0` (identical) to `21.0` (black on white).
 *
 * Lives in test source: it exists only to make "the needle is invisible" an assertion these tests
 * can check, and nothing in production reads it.
 */
fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance() + 0.05
    val lb = b.luminance() + 0.05
    return (max(la, lb) / min(la, lb)).toDouble()
}

/** WCAG AA for large text and graphical objects. A needle below this cannot be read at a glance. */
const val MIN_LEGIBLE_CONTRAST: Double = 3.0

/**
 * The gauge nobody can see.
 *
 * `GaugeTheme` has no background colour on purpose — that is what lets one renderer serve both a
 * dashboard tile and the HUD. So `ClassicAnalogGauge` fills no dial face, and whatever is behind
 * the tile becomes the face. On this app's dark surface that makes its near-black needle a black
 * needle on a black ground: it renders without error, its geometry is correct, and there is
 * nothing on the screen. No assertion about angles or sweeps catches that, so these do.
 */
class TileFaceTest {

    /** `CarScanDarkColors.surface`. Dark is the default: this app is used in a car, at night. */
    private val darkSurface = Color(0xFF14171B)

    @Test
    fun `the classic gauge is invisible on the dark surface — which is why a face exists`() {
        // The bug, stated as an assertion. If this ever stops being true, the face is unnecessary
        // and this whole file should go.
        contrastRatio(darkSurface, GaugeThemes.Classic.needle) shouldBeLessThan MIN_LEGIBLE_CONTRAST
    }

    @Test
    fun `a classic tile gets a light face, whatever the Material surface is doing`() {
        val face = tileFace(GaugeStyleId.CLASSIC_ANALOG, darkSurface)

        face shouldNotBe darkSurface
        contrastRatio(face, GaugeThemes.Classic.needle) shouldBeGreaterThan MIN_LEGIBLE_CONTRAST
    }

    /** The numerals are drawn in `text`, not `needle`, and they have to be readable too. */
    @Test
    fun `the classic numerals are readable on the face`() {
        val face = tileFace(GaugeStyleId.CLASSIC_ANALOG, darkSurface)
        contrastRatio(face, GaugeThemes.Classic.text) shouldBeGreaterThan MIN_LEGIBLE_CONTRAST
    }

    /**
     * ...and the rim must not vanish into the face. `GaugeThemes.Classic.track` is a light
     * parchment; a face of exactly that colour would erase the dial's outline.
     */
    @Test
    fun `the classic rim is still distinguishable from the face`() {
        val face = tileFace(GaugeStyleId.CLASSIC_ANALOG, darkSurface)
        face shouldNotBe GaugeThemes.Classic.track
        contrastRatio(face, GaugeThemes.Classic.track) shouldBeGreaterThan 1.02
    }

    /**
     * The modern arc is the opposite problem: a neon accent designed for a dark ground. Giving it
     * the classic's light face would make *it* the unreadable one.
     */
    @Test
    fun `a modern tile keeps the Material surface`() {
        tileFace(GaugeStyleId.MODERN_ARC, darkSurface) shouldBe darkSurface
        contrastRatio(darkSurface, GaugeThemes.ModernDark.value) shouldBeGreaterThan
            MIN_LEGIBLE_CONTRAST
    }

    @Test
    fun `a stale tile is still legible — dimmed is not the same as hidden`() {
        val face = tileFace(GaugeStyleId.CLASSIC_ANALOG, darkSurface)
        // Stale is deliberately low-contrast, but "--" on a dial the user cannot find at all is
        // indistinguishable from a crash.
        contrastRatio(face, GaugeThemes.Classic.stale) shouldBeGreaterThan 1.4
    }
}
