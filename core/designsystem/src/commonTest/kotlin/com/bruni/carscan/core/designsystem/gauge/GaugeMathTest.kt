package com.bruni.carscan.core.designsystem.gauge

import com.bruni.carscan.core.units.NumberFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Everything a gauge decides before it touches a pixel. Pixel output cannot be asserted, so
 * anything that could be wrong is pulled out to here.
 */
class GaugeMathTest {

    // --- valueToFraction ---------------------------------------------------

    @Test
    fun mapsMinToZeroMaxToOneAndMidpointToHalf() {
        assertEquals(0f, valueToFraction(0f, min = 0f, max = 8000f))
        assertEquals(1f, valueToFraction(8000f, min = 0f, max = 8000f))
        assertEquals(0.5f, valueToFraction(4000f, min = 0f, max = 8000f))
    }

    @Test
    fun mapsMidpointOfANonZeroMinimum() {
        // Coolant temperature: -40..120 °C. The midpoint is 40, not 60.
        assertEquals(0.5f, valueToFraction(40f, min = -40f, max = 120f))
    }

    @Test
    fun clampsBelowMin() {
        assertEquals(0f, valueToFraction(-500f, min = 0f, max = 8000f))
    }

    @Test
    fun clampsAboveMax() {
        // Never draw outside the arc: a bad decode must not sweep the needle past the end stop.
        assertEquals(1f, valueToFraction(99_999f, min = 0f, max = 8000f))
    }

    @Test
    fun degenerateRangeDoesNotDivideByZero() {
        // min == max. Naive (v-min)/(max-min) yields NaN or Infinity here.
        val f = valueToFraction(5f, min = 5f, max = 5f)
        assertTrue(f.isFinite(), "min == max must not produce $f")
        assertEquals(0f, f)
    }

    @Test
    fun invertedRangeIsTreatedAsDegenerate() {
        assertEquals(0f, valueToFraction(50f, min = 100f, max = 0f))
    }

    @Test
    fun nanValueDoesNotPropagate() {
        val f = valueToFraction(Float.NaN, min = 0f, max = 8000f)
        assertTrue(f.isFinite(), "NaN leaked into the drawing path as $f")
        assertEquals(0f, f)
    }

    @Test
    fun infiniteValueClampsToTheEnds() {
        assertEquals(1f, valueToFraction(Float.POSITIVE_INFINITY, min = 0f, max = 8000f))
        assertEquals(0f, valueToFraction(Float.NEGATIVE_INFINITY, min = 0f, max = 8000f))
    }

    @Test
    fun nonFiniteBoundsCollapseToZero() {
        assertEquals(0f, valueToFraction(50f, min = Float.NaN, max = 100f))
        assertEquals(0f, valueToFraction(50f, min = 0f, max = Float.POSITIVE_INFINITY))
    }

    // --- fractionToAngle ---------------------------------------------------

    @Test
    fun fractionZeroIsTheStartAngleAndOneIsTheEnd() {
        assertEquals(135f, fractionToAngle(0f, startAngle = 135f, sweepAngle = 270f))
        assertEquals(405f, fractionToAngle(1f, startAngle = 135f, sweepAngle = 270f))
    }

    @Test
    fun halfFractionIsHalfwayAlongTheSweep() {
        assertEquals(270f, fractionToAngle(0.5f, startAngle = 135f, sweepAngle = 270f))
    }

    // --- tickFraction ------------------------------------------------------

    @Test
    fun firstAndLastTickLandExactlyOnTheEnds() {
        assertEquals(0f, tickFraction(index = 0, count = 7))
        assertEquals(1f, tickFraction(index = 6, count = 7))
    }

    @Test
    fun ticksAreEvenlySpaced() {
        val count = 9
        val step = tickFraction(1, count) - tickFraction(0, count)
        for (i in 1 until count) {
            val gap = tickFraction(i, count) - tickFraction(i - 1, count)
            assertEquals(step, gap, absoluteTolerance = 1e-6f, "gap before tick $i")
        }
    }

    @Test
    fun aSingleTickSitsAtTheStartRatherThanDividingByZero() {
        assertEquals(0f, tickFraction(index = 0, count = 1))
    }

    // --- bands (optimal zone, redline zone) --------------------------------

    @Test
    fun absentBandIsNull() {
        assertNull(bandFraction(from = null, to = 90f, min = 0f, max = 120f))
        assertNull(bandFraction(from = 80f, to = null, min = 0f, max = 120f))
    }

    @Test
    fun bandIsExpressedAsFractionsOfTheSweep() {
        val band = bandFraction(from = 80f, to = 100f, min = 0f, max = 200f)
        assertEquals(GaugeBand(startFraction = 0.4f, endFraction = 0.5f), band)
    }

    @Test
    fun bandIsClampedToTheGaugeRange() {
        // A redline that starts below min, or an optimal band that runs past max, must not
        // draw arc outside the gauge.
        val band = bandFraction(from = -50f, to = 500f, min = 0f, max = 200f)
        assertEquals(GaugeBand(startFraction = 0f, endFraction = 1f), band)
    }

    @Test
    fun bandEntirelyOutsideTheRangeIsNull() {
        assertNull(bandFraction(from = 300f, to = 400f, min = 0f, max = 200f))
        assertNull(bandFraction(from = -400f, to = -300f, min = 0f, max = 200f))
    }

    @Test
    fun zeroWidthOrInvertedBandIsNull() {
        assertNull(bandFraction(from = 100f, to = 100f, min = 0f, max = 200f))
        assertNull(bandFraction(from = 150f, to = 50f, min = 0f, max = 200f))
    }

    @Test
    fun nonFiniteBandIsNull() {
        assertNull(bandFraction(from = Float.NaN, to = 100f, min = 0f, max = 200f))
        assertNull(bandFraction(from = 0f, to = 100f, min = Float.NaN, max = 200f))
    }

    @Test
    fun degenerateGaugeRangeHasNoBand() {
        assertNull(bandFraction(from = 5f, to = 5f, min = 5f, max = 5f))
    }

    @Test
    fun redlineRunsFromItsThresholdToTheTopOfTheGauge() {
        // How a renderer asks for the redline zone: from redlineFrom, to max.
        val band = bandFraction(from = 6000f, to = 8000f, min = 0f, max = 8000f)
        assertEquals(GaugeBand(startFraction = 0.75f, endFraction = 1f), band)
    }

    // --- polar placement (needle tip, tick ends, tick labels) --------------

    @Test
    fun zeroDegreesPointsRightAndNinetyPointsDownTheScreen() {
        // Screen coordinates: y grows downward, which is also the convention drawArc uses.
        assertEquals(10f, polarX(angleDegrees = 0f, radius = 10f), absoluteTolerance = 1e-4f)
        assertEquals(0f, polarY(angleDegrees = 0f, radius = 10f), absoluteTolerance = 1e-4f)

        assertEquals(0f, polarX(angleDegrees = 90f, radius = 10f), absoluteTolerance = 1e-4f)
        assertEquals(10f, polarY(angleDegrees = 90f, radius = 10f), absoluteTolerance = 1e-4f)
    }

    @Test
    fun the135DegreeStartOfADialSitsLowerLeftOfTheCentre() {
        val r = 100f
        assertTrue(polarX(135f, r) < 0f, "start angle must be left of centre")
        assertTrue(polarY(135f, r) > 0f, "start angle must be below centre")
    }

    // --- formatGaugeValue --------------------------------------------------

    private val en = NumberFormatter("en")

    @Test
    fun formatsWithTheRequestedNumberOfDecimals() {
        assertEquals("1726", formatGaugeValue(1726.4f, decimals = 0, formatter = en))
        assertEquals("1726.4", formatGaugeValue(1726.42f, decimals = 1, formatter = en))
        assertEquals("13.80", formatGaugeValue(13.8f, decimals = 2, formatter = en))
    }

    @Test
    fun roundsRatherThanTruncates() {
        assertEquals("2", formatGaugeValue(1.6f, decimals = 0, formatter = en))
        assertEquals("0.3", formatGaugeValue(0.25f, decimals = 1, formatter = en))
    }

    @Test
    fun keepsTheSignOnNegativeValues() {
        // Intake air temperature goes below zero, and "-" must not be lost to the rounding.
        assertEquals("-7", formatGaugeValue(-7.2f, decimals = 0, formatter = en))
        assertEquals("-0.5", formatGaugeValue(-0.5f, decimals = 1, formatter = en))
    }

    @Test
    fun padsTheFractionalPart() {
        assertEquals("5.00", formatGaugeValue(5f, decimals = 2, formatter = en))
        assertEquals("5.05", formatGaugeValue(5.05f, decimals = 2, formatter = en))
    }

    @Test
    fun aNonFiniteValueShowsTheNoReadingPlaceholderRatherThanNaN() {
        // "NaN" on a dashboard reads as a crash. A dash reads as "no reading".
        assertEquals(NO_READING, formatGaugeValue(Float.NaN, decimals = 0, formatter = en))
        assertEquals(
            NO_READING,
            formatGaugeValue(Float.POSITIVE_INFINITY, decimals = 1, formatter = en),
        )
    }

    @Test
    fun writesTheNumberTheWayTheLocaleWritesIt() {
        // The separator used to be a hard-coded '.', which is wrong in six of the eight
        // locales we ship. The rounding contract above is unchanged; only the writing is.
        assertEquals("13,80", formatGaugeValue(13.8f, decimals = 2, formatter = NumberFormatter("de")))
        assertEquals("1726,4", formatGaugeValue(1726.42f, decimals = 1, formatter = NumberFormatter("ru")))
        // ...and grouping stays off, or German 1726 rpm would read "1.726".
        assertEquals("1726", formatGaugeValue(1726.4f, decimals = 0, formatter = NumberFormatter("de")))
    }
}
