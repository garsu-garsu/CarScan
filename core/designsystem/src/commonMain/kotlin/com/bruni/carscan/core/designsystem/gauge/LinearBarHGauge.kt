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
 * A horizontal fill bar: a rounded track spanning the tile's width, filled left-to-right in
 * proportion to the value. The optimal/redline zones are drawn as a thinner rail centred on the
 * track, at the same horizontal position [ModernArcGauge] would draw them at as an angle — here
 * they are just [GaugeMath] fractions of the width instead of the sweep.
 */
class LinearBarHGauge : GaugeRenderer {

    override val id: GaugeStyleId get() = GaugeStyleId.LINEAR_BAR_H

    override fun DrawScope.draw(spec: GaugeSpec, theme: GaugeTheme, animatedValue: Float) {
        val barHeight = size.height * BAR_HEIGHT_FRACTION
        if (barHeight <= 0f || size.width <= 0f) return
        val barTop = (size.height - barHeight) / 2f
        val corner = CornerRadius(barHeight / 2f)

        drawRoundRect(
            color = theme.track,
            topLeft = Offset(0f, barTop),
            size = Size(size.width, barHeight),
            cornerRadius = corner,
        )

        val zoneHeight = barHeight * ZONE_HEIGHT_FRACTION
        val zoneTop = barTop + (barHeight - zoneHeight) / 2f
        drawZone(
            band = bandFraction(spec.optimalFrom, spec.optimalTo, spec.min, spec.max),
            color = theme.optimal,
            zoneTop = zoneTop,
            zoneHeight = zoneHeight,
        )
        drawZone(
            band = bandFraction(spec.redlineFrom, spec.max, spec.min, spec.max),
            color = theme.redline,
            zoneTop = zoneTop,
            zoneHeight = zoneHeight,
        )

        if (spec.isStale) {
            // A dashed outline of the whole track. An empty fill would look exactly like a
            // reading of zero.
            drawRoundRect(
                color = theme.stale,
                topLeft = Offset(0f, barTop),
                size = Size(size.width, barHeight),
                cornerRadius = corner,
                style = Stroke(
                    width = barHeight * 0.2f,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(barHeight * 0.6f, barHeight * 0.9f),
                    ),
                ),
            )
            return
        }

        val fraction = valueToFraction(animatedValue, spec.min, spec.max)
        if (fraction <= 0f) return

        val redlineFrom = spec.redlineFrom
        val overRedline = redlineFrom != null && !animatedValue.isNaN() && animatedValue >= redlineFrom
        drawRoundRect(
            color = if (overRedline) theme.redline else theme.value,
            topLeft = Offset(0f, barTop),
            size = Size(size.width * fraction, barHeight),
            cornerRadius = corner,
        )
    }

    private fun DrawScope.drawZone(band: GaugeBand?, color: Color, zoneTop: Float, zoneHeight: Float) {
        if (band == null) return
        val x = band.startFraction * size.width
        val width = (band.endFraction - band.startFraction) * size.width
        drawRoundRect(
            color = color,
            topLeft = Offset(x, zoneTop),
            size = Size(width, zoneHeight),
            cornerRadius = CornerRadius(zoneHeight / 2f),
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
        const val BAR_HEIGHT_FRACTION = 0.22f
        const val ZONE_HEIGHT_FRACTION = 0.5f

        const val VALUE_TEXT_FRACTION = 0.22f
        const val LABEL_TEXT_FRACTION = 0.08f
    }
}
