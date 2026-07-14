package com.bruni.carscan.core.designsystem.gauge

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import com.bruni.carscan.core.designsystem.theme.LocalNumberFormatter
import kotlin.math.roundToInt

/**
 * The torque-style dial: a needle on a pivot, major and minor ticks, a numbered scale and a
 * redline zone.
 *
 * Every tick and the needle are placed by [tickFraction], [fractionToAngle] and [polarX] /
 * [polarY], which are unit-tested; this file turns those numbers into strokes. The scale
 * numerals are real `Text` in the [Overlay], positioned by the same maths — `drawText` would
 * not scale with the user's font size and would mangle non-Latin scripts.
 */
class ClassicAnalogGauge : GaugeRenderer {

    override val id: GaugeStyleId get() = GaugeStyleId.CLASSIC_ANALOG

    override fun DrawScope.draw(spec: GaugeSpec, theme: GaugeTheme, animatedValue: Float) {
        val radius = size.minDimension / 2f
        if (radius <= 0f) return
        val centre = center

        val rimWidth = radius * RIM_STROKE_FRACTION
        val rimRadius = radius - rimWidth / 2f
        val rimDiameter = rimRadius * 2f
        val topLeft = Offset(centre.x - rimRadius, centre.y - rimRadius)
        val rimSize = Size(rimDiameter, rimDiameter)

        drawArc(
            color = theme.track,
            startAngle = START_ANGLE,
            sweepAngle = SWEEP_ANGLE,
            useCenter = false,
            topLeft = topLeft,
            size = rimSize,
            style = Stroke(width = rimWidth, cap = StrokeCap.Butt),
        )

        drawZone(
            band = bandFraction(spec.optimalFrom, spec.optimalTo, spec.min, spec.max),
            color = theme.optimal,
            topLeft = topLeft,
            rimSize = rimSize,
            rimWidth = rimWidth,
        )
        drawZone(
            band = bandFraction(spec.redlineFrom, spec.max, spec.min, spec.max),
            color = theme.redline,
            topLeft = topLeft,
            rimSize = rimSize,
            rimWidth = rimWidth,
        )

        drawTicks(theme, radius, centre, rimWidth)

        if (spec.isStale) {
            // No needle at all, plus a dashed rim. A needle parked at min is exactly what a
            // reading of zero looks like on most gauges, so parking it would be a lie.
            drawArc(
                color = theme.stale,
                startAngle = START_ANGLE,
                sweepAngle = SWEEP_ANGLE,
                useCenter = false,
                topLeft = topLeft,
                size = rimSize,
                style = Stroke(
                    width = rimWidth,
                    cap = StrokeCap.Butt,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(rimWidth * 1.5f, rimWidth * 2f),
                    ),
                ),
            )
            drawCircle(color = theme.stale, radius = radius * HUB_RADIUS_FRACTION, center = centre)
            return
        }

        val fraction = valueToFraction(animatedValue, spec.min, spec.max)
        val angle = fractionToAngle(fraction, START_ANGLE, SWEEP_ANGLE)
        val needleLength = radius * NEEDLE_LENGTH_FRACTION
        drawLine(
            color = theme.needle,
            start = centre,
            end = Offset(
                x = centre.x + polarX(angle, needleLength),
                y = centre.y + polarY(angle, needleLength),
            ),
            strokeWidth = radius * NEEDLE_WIDTH_FRACTION,
            cap = StrokeCap.Round,
        )
        drawCircle(color = theme.needle, radius = radius * HUB_RADIUS_FRACTION, center = centre)
    }

    private fun DrawScope.drawZone(
        band: GaugeBand?,
        color: Color,
        topLeft: Offset,
        rimSize: Size,
        rimWidth: Float,
    ) {
        if (band == null) return
        drawArc(
            color = color,
            startAngle = fractionToAngle(band.startFraction, START_ANGLE, SWEEP_ANGLE),
            sweepAngle = (band.endFraction - band.startFraction) * SWEEP_ANGLE,
            useCenter = false,
            topLeft = topLeft,
            size = rimSize,
            style = Stroke(width = rimWidth, cap = StrokeCap.Butt),
        )
    }

    private fun DrawScope.drawTicks(
        theme: GaugeTheme,
        radius: Float,
        centre: Offset,
        rimWidth: Float,
    ) {
        val outer = radius - rimWidth
        val majorInner = outer - radius * MAJOR_TICK_LENGTH_FRACTION
        val minorInner = outer - radius * MINOR_TICK_LENGTH_FRACTION

        // Minor ticks first, so a major tick is never half-covered by its neighbour's stroke.
        val minorPositions = (MAJOR_TICKS - 1) * MINOR_TICKS_PER_MAJOR + 1
        for (i in 0 until minorPositions) {
            if (i % MINOR_TICKS_PER_MAJOR == 0) continue // a major tick stands here
            drawTick(
                theme.track,
                fractionToAngle(tickFraction(i, minorPositions), START_ANGLE, SWEEP_ANGLE),
                centre,
                minorInner,
                outer,
                radius * MINOR_TICK_WIDTH_FRACTION,
            )
        }
        for (i in 0 until MAJOR_TICKS) {
            drawTick(
                theme.text,
                fractionToAngle(tickFraction(i, MAJOR_TICKS), START_ANGLE, SWEEP_ANGLE),
                centre,
                majorInner,
                outer,
                radius * MAJOR_TICK_WIDTH_FRACTION,
            )
        }
    }

    private fun DrawScope.drawTick(
        color: Color,
        angle: Float,
        centre: Offset,
        innerRadius: Float,
        outerRadius: Float,
        width: Float,
    ) {
        drawLine(
            color = color,
            start = Offset(
                x = centre.x + polarX(angle, innerRadius),
                y = centre.y + polarY(angle, innerRadius),
            ),
            end = Offset(
                x = centre.x + polarX(angle, outerRadius),
                y = centre.y + polarY(angle, outerRadius),
            ),
            strokeWidth = width,
            cap = StrokeCap.Butt,
        )
    }

    @Composable
    override fun Overlay(spec: GaugeSpec, theme: GaugeTheme) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            val formatter = LocalNumberFormatter.current
            val extent = minOf(constraints.maxWidth, constraints.maxHeight).toFloat()
            val labelRadius = extent / 2f * SCALE_LABEL_RADIUS_FRACTION
            val span = spec.max - spec.min

            if (span > 0f && span.isFinite()) {
                repeat(MAJOR_TICKS) { i ->
                    val fraction = tickFraction(i, MAJOR_TICKS)
                    val angle = fractionToAngle(fraction, START_ANGLE, SWEEP_ANGLE)
                    val dx = polarX(angle, labelRadius)
                    val dy = polarY(angle, labelRadius)
                    BasicText(
                        text = formatGaugeValue(spec.min + fraction * span, 0, formatter),
                        // Centred, then pushed out to the tick: the label's own centre lands on
                        // the point, so it never has to be measured.
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) },
                        style = TextStyle(
                            color = theme.text,
                            fontSize = with(density) { (extent * SCALE_TEXT_FRACTION).toSp() },
                        ),
                    )
                }
            }

            GaugeReadout(
                spec = spec,
                theme = theme,
                valueFontSize = with(density) { (extent * VALUE_TEXT_FRACTION).toSp() },
                labelFontSize = with(density) { (extent * LABEL_TEXT_FRACTION).toSp() },
                // In the open bottom of the dial, where a real cluster puts its readout.
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(0, (extent * READOUT_OFFSET_FRACTION).roundToInt()) },
            )
        }
    }

    private companion object {
        /** 240° opening at the bottom: 150° (lower left) round to 390° (lower right). */
        const val START_ANGLE = 150f
        const val SWEEP_ANGLE = 240f

        const val MAJOR_TICKS = 7
        const val MINOR_TICKS_PER_MAJOR = 4

        const val RIM_STROKE_FRACTION = 0.06f
        const val MAJOR_TICK_LENGTH_FRACTION = 0.14f
        const val MINOR_TICK_LENGTH_FRACTION = 0.07f
        const val MAJOR_TICK_WIDTH_FRACTION = 0.030f
        const val MINOR_TICK_WIDTH_FRACTION = 0.014f

        const val NEEDLE_LENGTH_FRACTION = 0.74f
        const val NEEDLE_WIDTH_FRACTION = 0.035f
        const val HUB_RADIUS_FRACTION = 0.07f

        const val SCALE_LABEL_RADIUS_FRACTION = 0.66f
        const val SCALE_TEXT_FRACTION = 0.062f
        const val VALUE_TEXT_FRACTION = 0.13f
        const val LABEL_TEXT_FRACTION = 0.06f
        const val READOUT_OFFSET_FRACTION = 0.24f
    }
}
