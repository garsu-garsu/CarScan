package com.bruni.carscan.core.designsystem.theme

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.designsystem.gauge.Gauge
import com.bruni.carscan.core.designsystem.gauge.GaugeSpec
import com.bruni.carscan.core.designsystem.gauge.GaugeStyleId
import com.bruni.carscan.core.designsystem.gauge.GaugeTestTags
import com.bruni.carscan.core.designsystem.gauge.LocalGaugeRenderer
import com.bruni.carscan.core.units.NumberFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The theme is the thing that binds a *choice* — the gauge style the user picked, the locale
 * the phone is set to — to the things that draw. Both bindings are asserted through what
 * actually reaches the screen, because a `CompositionLocal` that is provided but never read
 * is indistinguishable from one that was never provided.
 */
@OptIn(ExperimentalTestApi::class)
class CarScanThemeUiTest {

    private val battery = GaugeSpec(
        label = "Battery",
        value = 13.84f,
        min = 0f,
        max = 16f,
        unitLabel = "V",
        decimals = 1,
    )

    @Test
    fun theThemeBindsTheGaugeStyleTheUserPicked() = runComposeUiTest {
        var modern: GaugeStyleId? = null
        var classic: GaugeStyleId? = null

        setContent {
            CarScanTheme(gaugeStyle = GaugeStyleId.MODERN_ARC) {
                modern = LocalGaugeRenderer.current.id
            }
            CarScanTheme(gaugeStyle = GaugeStyleId.CLASSIC_ANALOG) {
                classic = LocalGaugeRenderer.current.id
            }
        }
        waitForIdle()

        assertEquals(GaugeStyleId.MODERN_ARC, modern)
        assertEquals(GaugeStyleId.CLASSIC_ANALOG, classic)
    }

    @Test
    fun theThemeSuppliesTheColoursThatMatchTheChosenStyle() = runComposeUiTest {
        var theme: com.bruni.carscan.core.designsystem.gauge.GaugeTheme? = null

        setContent {
            CarScanTheme(gaugeStyle = GaugeStyleId.CLASSIC_ANALOG) {
                theme = LocalGaugeTheme.current
            }
        }
        waitForIdle()

        assertEquals(GaugeThemes.Classic, theme)
    }

    @Test
    fun aGaugeReadsOutInTheLocalesOwnDecimalSeparator() = runComposeUiTest {
        // The bug this whole module exists to kill: a German user reading "13.8" where their
        // language writes "13,8". Six of the eight shipping locales use a comma.
        setContent {
            CompositionLocalProvider(LocalNumberFormatter provides NumberFormatter("de")) {
                Gauge(
                    spec = battery,
                    theme = GaugeThemes.ModernDark,
                    renderer = com.bruni.carscan.core.designsystem.gauge.ModernArcGauge(),
                    modifier = Modifier.size(200.dp),
                )
            }
        }

        onNodeWithTag(GaugeTestTags.VALUE).assertTextEquals("13,8")
    }

    @Test
    fun theSameGaugeReadsOutWithADotInEnglish() = runComposeUiTest {
        // The other half of the pair. Asserting only one locale is what let the hard-coded
        // '.' pass its tests in the first place.
        setContent {
            CompositionLocalProvider(LocalNumberFormatter provides NumberFormatter("en")) {
                Gauge(
                    spec = battery,
                    theme = GaugeThemes.ModernDark,
                    renderer = com.bruni.carscan.core.designsystem.gauge.ModernArcGauge(),
                    modifier = Modifier.size(200.dp),
                )
            }
        }

        onNodeWithTag(GaugeTestTags.VALUE).assertTextEquals("13.8")
    }

    @Test
    fun theHudPaletteCanReplaceTheThemesWithoutReplacingTheTheme() = runComposeUiTest {
        // The HUD is not a Material surface, but it draws the same renderers. It gets there by
        // overriding one CompositionLocal, not by owning a second theme.
        var seen: com.bruni.carscan.core.designsystem.gauge.GaugeTheme? = null

        setContent {
            CarScanTheme(gaugeStyle = GaugeStyleId.MODERN_ARC) {
                CompositionLocalProvider(LocalGaugeTheme provides GaugeThemes.Hud) {
                    seen = LocalGaugeTheme.current
                }
            }
        }
        waitForIdle()

        assertEquals(GaugeThemes.Hud, seen)
    }
}
