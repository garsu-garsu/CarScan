package com.bruni.carscan.core.transport.fake

import kotlin.time.TimeSource

/**
 * Where the emulator gets "now" from.
 *
 * It is a parameter, not a call to a real clock, because every test that drives the
 * emulator runs on `runTest`'s virtual time. A wall clock here would make the driving
 * simulator non-reproducible and every timing assertion flaky.
 */
fun interface ElmClock {
    fun nowMs(): Long

    companion object {
        /** For the debug build talking to a real user, where wall time is the point. */
        fun monotonic(): ElmClock {
            val start = TimeSource.Monotonic.markNow()
            return ElmClock { start.elapsedNow().inWholeMilliseconds }
        }
    }
}
