package com.bruni.carscan.core.designsystem.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import com.bruni.carscan.core.designsystem.theme.LocalNumberFormatter

/** Test tags for the numerals a user actually reads off the tile. */
object GaugeTestTags {
    const val VALUE = "gauge_value"
    const val UNIT = "gauge_unit"
    const val LABEL = "gauge_label"
}

/**
 * One gauge tile: the [renderer]'s canvas with its numerals composed on top.
 *
 * [animatedValue] is the smoothed value the needle or arc follows and is the caller's job —
 * see [GaugeRenderer]. It defaults to the raw value, which is what a HUD or a screenshot
 * wants. The numerals always show [GaugeSpec.value] itself, never the smoothed value: a
 * readout that lags the number it is reporting is just wrong.
 */
@Composable
fun Gauge(
    spec: GaugeSpec,
    theme: GaugeTheme,
    renderer: GaugeRenderer = LocalGaugeRenderer.current,
    modifier: Modifier = Modifier,
    animatedValue: Float = spec.value,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            with(renderer) { draw(spec, theme, animatedValue) }
        }
        renderer.Overlay(spec, theme)
    }
}

/**
 * The numerals, as real [BasicText] rather than `drawText`, so they pick up font scaling and
 * the app's locale — including scripts the renderer has never heard of.
 *
 * [BasicText] rather than Material's `Text` because a gauge also renders inside the HUD,
 * which is not a Material surface and supplies its own high-contrast [GaugeTheme].
 */
@Composable
internal fun GaugeReadout(
    spec: GaugeSpec,
    theme: GaugeTheme,
    valueFontSize: TextUnit,
    labelFontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    // Stale text is drawn in the stale colour, so a tile with no reading is dim as well as
    // dashed — the same signal the renderer paints on the dial itself.
    val supportingColor = if (spec.isStale) theme.stale else theme.text
    val formatter = LocalNumberFormatter.current

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            text = spec.label,
            modifier = Modifier.testTag(GaugeTestTags.LABEL),
            style = TextStyle(
                color = supportingColor,
                fontSize = labelFontSize,
                textAlign = TextAlign.Center,
            ),
        )
        BasicText(
            text = if (spec.isStale) {
                NO_READING
            } else {
                formatGaugeValue(spec.value, spec.decimals, formatter)
            },
            modifier = Modifier.testTag(GaugeTestTags.VALUE),
            style = TextStyle(
                color = supportingColor,
                fontSize = valueFontSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
        )
        BasicText(
            text = spec.unitLabel,
            modifier = Modifier.testTag(GaugeTestTags.UNIT),
            style = TextStyle(
                color = supportingColor,
                fontSize = labelFontSize,
                textAlign = TextAlign.Center,
            ),
        )
    }
}
