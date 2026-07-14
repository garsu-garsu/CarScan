package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.obd.session.AtStateCache
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** What `freq` means, and that meeting a fast command does not starve a slow one. */
class PollRateTest {

    private fun TestScope.clock() = PollClock { testScheduler.currentTime }

    @Test
    fun `freq is seconds between requests, not hertz`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 25)
        exchanger.replies["010C"] = listOf(RPM_FRAME)
        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())

        // OBDb writes 0.25 to mean "four times a second". Read as hertz it is 250 Hz, which
        // would spend an entire clone's budget — 15 queries/second — on this one command.
        scheduler.submit(listOf(command(pid = "0C", freq = 0.25).pollEntry()))
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(10_000)

        val fires = exchanger.countOf("010C")
        assertTrue(fires in 38..42, "0.25 s between requests over 10 s is ~40 polls, got $fires")
    }

    @Test
    fun `a 10 Hz command runs at 10 Hz and a 0,5 Hz command is not starved`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 25)
        exchanger.replies["010C"] = listOf(RPM_FRAME)
        exchanger.replies["0105"] = listOf("7E8 03 41 05 5A")
        exchanger.replies["012F"] = listOf("7E8 03 41 2F 80")
        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())

        scheduler.submit(
            listOf(
                command(pid = "0C", freq = 0.1).pollEntry(Priority.CRITICAL),
                command(pid = "05", freq = 0.5).pollEntry(Priority.NORMAL),
                command(pid = "2F", freq = 2.0).pollEntry(Priority.BACKGROUND),
            ),
        )
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(10_000)

        // 12.5 Hz of demand against a 40 Hz adapter: everything fits, so everything gets
        // exactly what it asked for.
        val fast = exchanger.countOf("010C")
        val medium = exchanger.countOf("0105")
        val slow = exchanger.countOf("012F")

        assertTrue(fast in 90..101, "freq 0.1 over 10 s is ~100 polls (9-10 Hz), got $fast")
        assertTrue(medium in 18..21, "freq 0.5 over 10 s is ~20 polls, got $medium")
        assertTrue(slow in 4..6, "freq 2.0 over 10 s is ~5 polls and must not be starved, got $slow")
    }
}
