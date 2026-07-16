package com.bruni.carscan.core.designsystem.gauge

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.bruni.carscan.core.designsystem.theme.LocalNumberFormatter
import com.bruni.carscan.core.units.NumberFormatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pixels cannot be asserted, so these prove only what a smoke test can: that every renderer
 * actually reaches its `draw` for every hostile input without throwing, and that the numerals
 * the user reads are the numerals the spec carries.
 *
 * The angle, clamping and band maths they depend on are asserted in `GaugeMathTest`.
 *
 * **In `jvmTest`, not `commonTest`, on purpose.** `runComposeUiTest` needs a real Compose host.
 * On the JVM target it gets a headless Skiko one; under `androidHostTest` — a plain JVM unit
 * test with no device — it dies on `android.os.Build.FINGERPRINT` being null. The code under
 * test is still `commonMain` and still Apple-safe; only the harness is host-specific.
 */
@OptIn(ExperimentalTestApi::class)
class GaugeRenderersUiTest {

    private val theme = GaugeTheme(
        track = Color.DarkGray,
        value = Color.Cyan,
        needle = Color.White,
        text = Color.White,
        optimal = Color.Green,
        redline = Color.Red,
        stale = Color.Gray,
    )

    private fun rpm(value: Float, isStale: Boolean = false) = GaugeSpec(
        label = "Engine speed",
        value = value,
        min = 0f,
        max = 8000f,
        unitLabel = "rpm",
        decimals = 0,
        optimalFrom = 1500f,
        optimalTo = 3000f,
        redlineFrom = 6000f,
        isStale = isStale,
    )

    /** Counts the draws the renderer under test actually performed. */
    private class CountingRenderer(private val delegate: GaugeRenderer) : GaugeRenderer {
        var draws = 0
            private set

        override val id: GaugeStyleId get() = delegate.id

        override fun DrawScope.draw(spec: GaugeSpec, theme: GaugeTheme, animatedValue: Float) {
            draws++
            with(delegate) { draw(spec, theme, animatedValue) }
        }

        @Composable
        override fun Overlay(spec: GaugeSpec, theme: GaugeTheme) = delegate.Overlay(spec, theme)
    }

    private fun drawsEveryEdgeCaseWithoutCrashing(delegate: GaugeRenderer) = runComposeUiTest {
        val renderer = CountingRenderer(delegate)
        var spec by mutableStateOf(rpm(0f))

        setContent {
            // The gauge reads LocalNumberFormatter, which now fails loudly outside a theme
            // rather than defaulting silently to English. Provide it explicitly.
            CompositionLocalProvider(LocalNumberFormatter provides NumberFormatter("en")) {
            Gauge(
                spec = spec,
                theme = theme,
                animatedValue = spec.value,
                renderer = renderer,
                modifier = Modifier.size(200.dp),
            )
                    }
        }

        val hostile = listOf(
            rpm(0f),                              // min
            rpm(4000f),                           // mid
            rpm(8000f),                           // max
            rpm(-1000f),                          // below min — must clamp, not draw backwards
            rpm(99_999f),                         // above max — must clamp, not overrun the arc
            rpm(Float.NaN),                       // a failed decode
            rpm(Float.POSITIVE_INFINITY),
            rpm(0f, isStale = true),              // no reading at all
        )
        for (s in hostile) {
            spec = s
            waitForIdle()
        }

        assertTrue(
            renderer.draws > 0,
            "the renderer never drew — this test would pass on a gauge that renders nothing",
        )
    }

    @Test
    fun modernArcDrawsEveryEdgeCaseWithoutCrashing() =
        drawsEveryEdgeCaseWithoutCrashing(ModernArcGauge())

    @Test
    fun classicAnalogDrawsEveryEdgeCaseWithoutCrashing() =
        drawsEveryEdgeCaseWithoutCrashing(ClassicAnalogGauge())

    @Test
    fun semicircleDrawsEveryEdgeCaseWithoutCrashing() =
        drawsEveryEdgeCaseWithoutCrashing(SemicircleGauge())

    @Test
    fun numericDrawsEveryEdgeCaseWithoutCrashing() =
        drawsEveryEdgeCaseWithoutCrashing(NumericGauge())

    @Test
    fun linearBarHDrawsEveryEdgeCaseWithoutCrashing() =
        drawsEveryEdgeCaseWithoutCrashing(LinearBarHGauge())

    @Test
    fun linearBarVDrawsEveryEdgeCaseWithoutCrashing() =
        drawsEveryEdgeCaseWithoutCrashing(LinearBarVGauge())

    @Test
    fun degenerateAndInvertedRangesDoNotCrashEitherRenderer() = runComposeUiTest {
        val degenerate = GaugeSpec(
            label = "Broken",
            value = 5f,
            min = 5f,
            max = 5f,          // min == max
            unitLabel = "",
            redlineFrom = 5f,
            optimalFrom = 5f,
            optimalTo = 5f,
        )
        setContent {
            // The gauge reads LocalNumberFormatter, which now fails loudly outside a theme
            // rather than defaulting silently to English. Provide it explicitly.
            CompositionLocalProvider(LocalNumberFormatter provides NumberFormatter("en")) {
            Gauge(degenerate, theme, ModernArcGauge(), Modifier.size(120.dp))
            Gauge(
                degenerate.copy(min = 100f, max = 0f),   // inverted
                theme,
                ClassicAnalogGauge(),
                Modifier.size(120.dp),
            )
                    }
        }
        waitForIdle()
    }

    private fun overlayShowsTheReading(renderer: GaugeRenderer) = runComposeUiTest {
        setContent {
            // The gauge reads LocalNumberFormatter, which now fails loudly outside a theme
            // rather than defaulting silently to English. Provide it explicitly.
            CompositionLocalProvider(LocalNumberFormatter provides NumberFormatter("en")) {
            Gauge(rpm(1726f), theme, renderer, Modifier.size(200.dp))
                    }
        }

        onNodeWithTag(GaugeTestTags.VALUE).assertTextEquals("1726")
        onNodeWithTag(GaugeTestTags.UNIT).assertTextEquals("rpm")
        onNodeWithTag(GaugeTestTags.LABEL).assertTextEquals("Engine speed")
    }

    @Test
    fun modernArcOverlayShowsTheReading() = overlayShowsTheReading(ModernArcGauge())

    @Test
    fun classicAnalogOverlayShowsTheReading() = overlayShowsTheReading(ClassicAnalogGauge())

    @Test
    fun semicircleOverlayShowsTheReading() = overlayShowsTheReading(SemicircleGauge())

    @Test
    fun numericOverlayShowsTheReading() = overlayShowsTheReading(NumericGauge())

    @Test
    fun linearBarHOverlayShowsTheReading() = overlayShowsTheReading(LinearBarHGauge())

    @Test
    fun linearBarVOverlayShowsTheReading() = overlayShowsTheReading(LinearBarVGauge())

    @Test
    fun overlayHonoursTheDecimalsOfTheSpec() = runComposeUiTest {
        setContent {
            // The gauge reads LocalNumberFormatter, which now fails loudly outside a theme
            // rather than defaulting silently to English. Provide it explicitly.
            CompositionLocalProvider(LocalNumberFormatter provides NumberFormatter("en")) {
            Gauge(
                spec = GaugeSpec(
                    label = "Battery",
                    value = 13.84f,
                    min = 0f,
                    max = 16f,
                    unitLabel = "V",
                    decimals = 1,
                ),
                theme = theme,
                renderer = ModernArcGauge(),
                modifier = Modifier.size(200.dp),
            )
                    }
        }

        onNodeWithTag(GaugeTestTags.VALUE).assertTextEquals("13.8")
    }

    @Test
    fun aStaleGaugeReadsAsNoReadingRatherThanZero() = runComposeUiTest {
        // The whole point of isStale: a tachometer that never got a value must not look like
        // an engine idling at 0 rpm.
        setContent {
            // The gauge reads LocalNumberFormatter, which now fails loudly outside a theme
            // rather than defaulting silently to English. Provide it explicitly.
            CompositionLocalProvider(LocalNumberFormatter provides NumberFormatter("en")) {
            Gauge(rpm(0f, isStale = true), theme, ModernArcGauge(), Modifier.size(200.dp))
                    }
        }

        onNodeWithTag(GaugeTestTags.VALUE).assertTextEquals(NO_READING)
    }

    @Test
    fun eachRendererReportsItsOwnStyleId() {
        assertEquals(GaugeStyleId.MODERN_ARC, ModernArcGauge().id)
        assertEquals(GaugeStyleId.CLASSIC_ANALOG, ClassicAnalogGauge().id)
        assertEquals(GaugeStyleId.SEMICIRCLE, SemicircleGauge().id)
        assertEquals(GaugeStyleId.NUMERIC, NumericGauge().id)
        assertEquals(GaugeStyleId.LINEAR_BAR_H, LinearBarHGauge().id)
        assertEquals(GaugeStyleId.LINEAR_BAR_V, LinearBarVGauge().id)
    }

    @Test
    fun degenerateAndInvertedRangesDoNotCrashTheNewShapesEither() = runComposeUiTest {
        val degenerate = GaugeSpec(
            label = "Broken",
            value = 5f,
            min = 5f,
            max = 5f,          // min == max
            unitLabel = "",
            redlineFrom = 5f,
            optimalFrom = 5f,
            optimalTo = 5f,
        )
        setContent {
            CompositionLocalProvider(LocalNumberFormatter provides NumberFormatter("en")) {
                Gauge(degenerate, theme, SemicircleGauge(), Modifier.size(120.dp))
                Gauge(degenerate, theme, NumericGauge(), Modifier.size(120.dp))
                Gauge(
                    degenerate.copy(min = 100f, max = 0f),   // inverted
                    theme,
                    LinearBarHGauge(),
                    Modifier.size(120.dp),
                )
                Gauge(
                    degenerate.copy(min = 100f, max = 0f),   // inverted
                    theme,
                    LinearBarVGauge(),
                    Modifier.size(120.dp),
                )
            }
        }
        waitForIdle()
    }
}
