package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.obd.session.AtStateCache
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A sample is stamped with wall time, never with milliseconds-since-the-scheduler-started.
 *
 * This test exists because the two used to be the same clock, and the damage was invisible from
 * inside either module. `SampleWriter` places a sample in a trip by computing
 * `sample.timestampMs - trip.startedMs`, and a trip's start is an epoch. Handed a monotonic stamp,
 * that subtraction yields a large negative offset, which the writer's own `if (secondsIn < 0)
 * return` guard then discards — so **every sample was dropped and no trip recorded anything.**
 *
 * :core:obd was correct. :core:database was correct. Both suites were green, because each supplied
 * a single fake clock that answered both questions identically. The bug lived in the seam between
 * them, which is exactly where unit tests do not look — so the fake clock in `PollFixtures` now
 * returns two deliberately incompatible numbers, and this asserts which one comes out.
 */
class PollSampleTimestampTest {

    private fun TestScope.clock() = testPollClock()

    @Test
    fun `a sample carries epoch time, not elapsed time`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 25)
        exchanger.replies["010C"] = listOf(RPM_FRAME)
        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())

        val seen = mutableListOf<Long>()
        backgroundScope.launch { scheduler.samples.collect { seen += it.timestampMs } }

        scheduler.submit(listOf(command(pid = "0C", freq = 0.25).pollEntry()))
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(2_000)

        assertTrue(seen.isNotEmpty(), "the scheduler emitted no samples at all")

        // The scheduler has been alive for two virtual seconds, so a monotonic stamp would be a
        // number in the low thousands. An epoch stamp is ~1.7e12. There is nothing plausible in
        // between, which is what makes this assertion sharp rather than arbitrary.
        val first = seen.first()
        assertTrue(
            first > 1_600_000_000_000L,
            "expected an epoch timestamp, got $first — that is elapsed time, and SampleWriter " +
                "subtracts the trip's epoch start from it and then silently drops the sample",
        )
    }
}
