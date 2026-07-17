package com.bruni.carscan.feature.trip

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** "2026-07-17 22:14", in the device's local time zone. */
internal fun formatDateTime(epochMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    @Suppress("DEPRECATION")
    val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
    @Suppress("DEPRECATION")
    return "${local.year}-${pad2(local.monthNumber)}-${pad2(local.dayOfMonth)} " +
        "${pad2(local.hour)}:${pad2(local.minute)}"
}

/** "1h 23m" past the hour mark, "12m" under it. Null means still recording — that's the screen's call. */
internal fun formatDuration(durationMs: Long): String {
    val totalMinutes = durationMs / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun pad2(n: Int): String = if (n < 10) "0$n" else n.toString()
