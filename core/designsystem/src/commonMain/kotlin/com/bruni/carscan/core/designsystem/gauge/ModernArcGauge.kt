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
 * The EV-cluster look: one 270° arc, a thick rounded stroke, big numerals, no ticks, no
 * needle. Everything it knows about angles and clamping comes from [GaugeMath], so this file
 * is only geometry.
 */
class ModernArcGauge : GaugeRenderer {

    override val id: GaugeStyleId get() = GaugeStyleId.MODERN_ARC

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

        // The zones sit as a thinner rail on top of the track, so they stay legible under the
        // value arc rather than being painted over by it.
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
            // A dashed ghost of the whole sweep. A value arc of zero length would be
            // indistinguishable from an engine sitting at 0 rpm.
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
        /** 270° of sweep opening at the bottom: 135° (lower left) round to 405° (lower right). */
        const val START_ANGLE = 135f
        const val SWEEP_ANGLE = 270f

        const val STROKE_FRACTION = 0.10f
        const val ZONE_STROKE_FRACTION = 0.30f

        const val VALUE_TEXT_FRACTION = 0.19f
        const val LABEL_TEXT_FRACTION = 0.075f
    }
}
