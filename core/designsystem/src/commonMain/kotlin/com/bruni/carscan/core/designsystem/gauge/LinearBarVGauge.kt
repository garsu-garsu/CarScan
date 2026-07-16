package com.bruni.carscan.core.designsystem.gauge

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity

/**
 * A vertical fill bar: a rounded track spanning the tile's height, filled bottom-to-top in
 * proportion to the value. The mirror of [LinearBarHGauge] — see it for the zone-drawing
 * rationale; here the same [GaugeMath] fractions run up the height instead of across the width.
 */
class LinearBarVGauge : GaugeRenderer {

    override val id: GaugeStyleId get() = GaugeStyleId.LINEAR_BAR_V

    override fun DrawScope.draw(spec: GaugeSpec, theme: GaugeTheme, animatedValue: Float) {
        val barWidth = size.width * BAR_WIDTH_FRACTION
        if (barWidth <= 0f || size.height <= 0f) return
        val barLeft = (size.width - barWidth) / 2f
        val corner = CornerRadius(barWidth / 2f)

        drawRoundRect(
            color = theme.track,
            topLeft = Offset(barLeft, 0f),
            size = Size(barWidth, size.height),
            cornerRadius = corner,
        )

        val zoneWidth = barWidth * ZONE_WIDTH_FRACTION
        val zoneLeft = barLeft + (barWidth - zoneWidth) / 2f
        drawZone(
            band = bandFraction(spec.optimalFrom, spec.optimalTo, spec.min, spec.max),
            color = theme.optimal,
            zoneLeft = zoneLeft,
            zoneWidth = zoneWidth,
        )
        drawZone(
            band = bandFraction(spec.redlineFrom, spec.max, spec.min, spec.max),
            color = theme.redline,
            zoneLeft = zoneLeft,
            zoneWidth = zoneWidth,
        )

        if (spec.isStale) {
            // A dashed outline of the whole track. An empty fill would look exactly like a
            // reading of zero.
            drawRoundRect(
                color = theme.stale,
                topLeft = Offset(barLeft, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = corner,
                style = Stroke(
                    width = barWidth * 0.2f,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(barWidth * 0.6f, barWidth * 0.9f),
                    ),
                ),
            )
            return
        }

        val fraction = valueToFraction(animatedValue, spec.min, spec.max)
        if (fraction <= 0f) return

        val redlineFrom = spec.redlineFrom
        val overRedline = redlineFrom != null && !animatedValue.isNaN() && animatedValue >= redlineFrom
        val fillHeight = size.height * fraction
        drawRoundRect(
            color = if (overRedline) theme.redline else theme.value,
            // Fills bottom→top: the top edge of the fill moves up as the fraction grows.
            topLeft = Offset(barLeft, size.height - fillHeight),
            size = Size(barWidth, fillHeight),
            cornerRadius = corner,
        )
    }

    private fun DrawScope.drawZone(band: GaugeBand?, color: Color, zoneLeft: Float, zoneWidth: Float) {
        if (band == null) return
        // A band's fractions run min→max, which is bottom→top here — so the higher fraction is
        // the higher point on screen, i.e. the smaller y.
        val yBottom = size.height - band.startFraction * size.height
        val yTop = size.height - band.endFraction * size.height
        drawRoundRect(
            color = color,
            topLeft = Offset(zoneLeft, yTop),
            size = Size(zoneWidth, yBottom - yTop),
            cornerRadius = CornerRadius(zoneWidth / 2f),
        )
    }

    @Composable
    override fun Overlay(spec: GaugeSpec, theme: GaugeTheme) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val extent = minOf(constraints.maxWidth, constraints.maxHeight).toFloat()
            GaugeReadout(
                spec = spec,
                theme = theme,
                valueFontSize = with(density) { (extent * VALUE_TEXT_FRACTION).toSp() },
                labelFontSize = with(density) { (extent * LABEL_TEXT_FRACTION).toSp() },
            )
        }
    }

    private companion object {
        const val BAR_WIDTH_FRACTION = 0.22f
        const val ZONE_WIDTH_FRACTION = 0.5f

        const val VALUE_TEXT_FRACTION = 0.22f
        const val LABEL_TEXT_FRACTION = 0.08f
    }
}
