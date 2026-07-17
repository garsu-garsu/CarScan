package com.bruni.carscan.feature.trip

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class TripFormattingTest {

    @Test
    fun `duration under an hour is shown in minutes only`() {
        assertEquals("12m", formatDuration(12 * 60_000L))
    }

    @Test
    fun `duration past an hour shows hours and minutes`() {
        assertEquals("1h 23m", formatDuration((60 + 23) * 60_000L))
    }

    @Test
    fun `date time is formatted in the given zone`() {
        // 2026-07-17T22:14:00Z.
        assertEquals("2026-07-17 22:14", formatDateTime(1_784_326_440_000L, TimeZone.UTC))
    }
}
