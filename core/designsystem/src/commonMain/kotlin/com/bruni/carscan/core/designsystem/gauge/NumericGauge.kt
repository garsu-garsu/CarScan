package com.bruni.carscan.core.designsystem.gauge

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity

/**
 * The "text" gauge: no dial graphic at all, just the big centered number [GaugeReadout] already
 * draws. [draw] paints only a subtle rounded track behind it — tinted by the current zone where
 * [GaugeMath] can tell us one — so a numeric tile is not entirely bare of the redline/optimal
 * signal the other shapes carry.
 */
class NumericGauge : GaugeRenderer {

    override val id: GaugeStyleId get() = GaugeStyleId.NUMERIC

    override fun DrawScope.draw(spec: GaugeSpec, theme: GaugeTheme, animatedValue: Float) {
        val strokeWidth = size.minDimension * STROKE_FRACTION
        val inset = strokeWidth / 2f
        val cornerRadius = size.minDimension * CORNER_FRACTION

        if (spec.isStale) {
            // A dashed outline rather than a solid one — the same "no reading" signal every
            // other renderer paints, just without a needle or arc to carry it.
            drawRoundRect(
                color = theme.stale,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius(cornerRadius),
                style = Stroke(
                    width = strokeWidth,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(strokeWidth * 1.5f, strokeWidth * 2f),
                    ),
                ),
            )
            return
        }

        val redlineFrom = spec.redlineFrom
        val optimalBand = bandFraction(spec.optimalFrom, spec.optimalTo, spec.min, spec.max)
        val valueFraction = valueToFraction(animatedValue, spec.min, spec.max)
        val overRedline = redlineFrom != null && !animatedValue.isNaN() && animatedValue >= redlineFrom
        val inOptimal = optimalBand != null && valueFraction in optimalBand.startFraction..optimalBand.endFraction

        val trackColor = when {
            overRedline -> theme.redline
            inOptimal -> theme.optimal
            else -> theme.track
        }

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = CornerRadius(cornerRadius),
            style = Stroke(width = strokeWidth),
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
                // The number is the whole point of this shape, so it gets more of the tile than
                // any other renderer gives it.
                valueFontSize = with(density) { (extent * VALUE_TEXT_FRACTION).toSp() },
                labelFontSize = with(density) { (extent * LABEL_TEXT_FRACTION).toSp() },
            )
        }
    }

    private companion object {
        const val STROKE_FRACTION = 0.03f
        const val CORNER_FRACTION = 0.12f

        const val VALUE_TEXT_FRACTION = 0.30f
        const val LABEL_TEXT_FRACTION = 0.09f
    }
}
