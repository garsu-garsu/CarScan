package com.bruni.carscan.feature.live

import com.bruni.carscan.core.data.SignalSeries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistorySeriesTest {

    /**
     * The whole point of storing NaN for a second the car never answered for: a hole must stay a
     * hole. Joined into one line, seconds 1 and 3 become adjacent points and Vico draws a
     * straight edge across the gap — a reading the car never reported, indistinguishable from one
     * it did.
     */
    @Test
    fun `a gap splits the trace instead of being drawn through`() {
        val series = SignalSeries("RPM", floatArrayOf(800f, 900f, Float.NaN, 1200f, 1300f))

        val segments = series.toSegments()

        assertEquals(2, segments.size, "the NaN second must break the trace in two")
        assertEquals(listOf(HistoryPoint(0, 800f), HistoryPoint(1, 900f)), segments[0])
        assertEquals(listOf(HistoryPoint(3, 1200f), HistoryPoint(4, 1300f)), segments[1])
        assertTrue(
            segments.none { seg -> seg.any { it.second == 2 } },
            "second 2 was never reported and must not appear as a point",
        )
    }

    /**
     * `values[t]` *is* the reading at second t — the chunks were laid down by t0_s, so the array
     * index is the x axis. Reindexing a segment from zero would slide every point after a gap
     * backwards in time.
     */
    @Test
    fun `x is the second since trip start, not the index within the segment`() {
        val series = SignalSeries("SPEED", FloatArray(600) { it.toFloat() })

        val points = series.toSegments().single()

        assertEquals(600, points.size)
        assertEquals(HistoryPoint(0, 0f), points.first())
        assertEquals(HistoryPoint(599, 599f), points.last())
    }

    @Test
    fun `a signal the car never answered for charts as nothing, not as a flat line at zero`() {
        val series = SignalSeries("COOLANT", FloatArray(10) { Float.NaN })

        assertEquals(emptyList(), series.toSegments())
    }

    /** A lone reading between two gaps is still a reading. Dropping it is losing data. */
    @Test
    fun `a single sample surrounded by gaps survives as its own segment`() {
        val series = SignalSeries("MAF", floatArrayOf(Float.NaN, 42f, Float.NaN))

        assertEquals(listOf(listOf(HistoryPoint(1, 42f))), series.toSegments())
    }
}
