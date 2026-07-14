package com.bruni.carscan.feature.live

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The x axis of a trip chart is seconds since it started. Drawn as a bare number, minute 30 of a
 * drive reads "1800" — which is not a time anybody reads off a chart.
 */
class ElapsedAxisTest {

    @Test
    fun `seconds are drawn as minutes and seconds`() {
        assertEquals("0:00", formatElapsed(0.0))
        assertEquals("0:07", formatElapsed(7.0))
        assertEquals("1:00", formatElapsed(60.0))
        assertEquals("30:47", formatElapsed(1847.0))
    }

    /** Past an hour the minutes have to be padded too, or 1:5:03 comes out for 1:05:03. */
    @Test
    fun `an hour in, the hours appear and the minutes are padded`() {
        assertEquals("1:00:00", formatElapsed(3600.0))
        assertEquals("1:05:03", formatElapsed(3903.0))
        assertEquals("2:00:01", formatElapsed(7201.0))
    }

    /** Vico hands its formatter the tick values it chose, which need not land on whole seconds. */
    @Test
    fun `a fractional tick truncates rather than rounding into the next second`() {
        assertEquals("0:59", formatElapsed(59.9))
    }
}
