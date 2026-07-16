package com.bruni.carscan.core.designsystem.gauge

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity

/**
 * A half-circle gauge: 180° of sweep across the top of the tile, left to right. Same rounded
 * stroke and banding conventions as [ModernArcGauge] — only the angles differ — so this file is,
 * again, only geometry over [GaugeMath].
 */
class SemicircleGauge : GaugeRenderer {

    override val id: GaugeStyleId get() = GaugeStyleId.SEMICIRCLE

    override fun DrawScope.draw(spec: GaugeSpec, theme: GaugeTheme, animatedValue: Float) {
        val strokeWidth = size.minDimension * STROKE_FRACTION
        val diameter = size.minDimension - strokeWidth
        if (diameter <= 0f) return

        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        val track = Stroke(width = strokeWidth, cap = StrokeCap.Round)

        drawArc(
            color = theme.track,
            startAngle = START_ANGLE,
            sweepAngle = SWEEP_ANGLE,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = track,
        )

        // Thinner rail on top of the track, so the zones stay legible under the value arc.
        val zone = Stroke(width = strokeWidth * ZONE_STROKE_FRACTION, cap = StrokeCap.Butt)
        drawBand(
            band = bandFraction(spec.optimalFrom, spec.optimalTo, spec.min, spec.max),
            color = theme.optimal,
            topLeft = topLeft,
            arcSize = arcSize,
            stroke = zone,
        )
        drawBand(
            band = bandFraction(spec.redlineFrom, spec.max, spec.min, spec.max),
            color = theme.redline,
            topLeft = topLeft,
            arcSize = arcSize,
            stroke = zone,
        )

        if (spec.isStale) {
            // A dashed ghost of the whole sweep — an empty value arc would look identical to a
            // reading of zero.
            drawArc(
                color = theme.stale,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Butt,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(strokeWidth * 0.6f, strokeWidth * 0.9f),
                    ),
                ),
            )
            return
        }

        val fraction = valueToFraction(animatedValue, spec.min, spec.max)
        if (fraction <= 0f) return

        val redlineFrom = spec.redlineFrom
        val overRedline = redlineFrom != null && !animatedValue.isNaN() && animatedValue >= redlineFrom
        drawArc(
            color = if (overRedline) theme.redline else theme.value,
            startAngle = START_ANGLE,
            sweepAngle = fraction * SWEEP_ANGLE,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = track,
        )
    }

    private fun DrawScope.drawBand(
        band: GaugeBand?,
        color: Color,
        topLeft: Offset,
        arcSize: Size,
        stroke: Stroke,
    ) {
        if (band == null) return
        drawArc(
            color = color,
            startAngle = fractionToAngle(band.startFraction, START_ANGLE, SWEEP_ANGLE),
            sweepAngle = (band.endFraction - band.startFraction) * SWEEP_ANGLE,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = stroke,
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
        /** 180° across the top: 180° (left) round to 360° (right), through 270° (straight up). */
        const val START_ANGLE = 180f
        const val SWEEP_ANGLE = 180f

        const val STROKE_FRACTION = 0.10f
        const val ZONE_STROKE_FRACTION = 0.30f

        const val VALUE_TEXT_FRACTION = 0.19f
        const val LABEL_TEXT_FRACTION = 0.075f
    }
}
