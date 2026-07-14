package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.obd.session.AtStateCache
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The idle slot.
 *
 * When nothing is due, the link still has to be kept warm — a silent ELM327 over BLE will
 * have its connection dropped by the phone, and an ECU that was put into a non-default
 * diagnostic session falls back out of it after five seconds of silence (the S3 timer),
 * taking the next multi-frame answer with it. So idle time is spent on the cheapest thing
 * that keeps both alive, and on nothing else: no busy-polling of the plan.
 */
class PollIdleTest {

    private fun TestScope.clock() = testPollClock()

    @Test
    fun `an idle scheduler keeps the link warm with ATRV at about 0,2 Hz`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 20, atLatencyMs = 2)
        exchanger.replies["010C"] = listOf(RPM_FRAME)

        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
        // freq 3600: OBDb's way of saying "once an hour". Nothing else is due for 30 seconds.
        scheduler.submit(listOf(command(pid = "0C", freq = 3600.0).pollEntry(Priority.BACKGROUND)))
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(30_000)

        val keepAlives = exchanger.countOf("ATRV")
        assertTrue(keepAlives in 4..7, "0.2 Hz over 30 s is ~6 keep-alives, got $keepAlives")
        exchanger.countOf("010C") shouldBe 1
    }

    /**
     * The adapter reset, so `ElmSession` re-initialized it and cleared every ECU session it was
     * tracking — the UDS session died on its own S3 timer while the adapter was away, and it
     * re-enters rather than assume it survived.
     *
     * Which means [AtStateCache.ecuSession] can go from non-empty to empty with nothing on the
     * scheduler's side having happened. Keep sending `3E00` after that and every keep-alive is a
     * tester-present addressed to a session that no longer exists — a wasted round trip, every two
     * seconds, on the resource we have least of. So the session map is *read* on each idle pass,
     * never cached across them.
     */
    @Test
    fun `a session lost to an adapter reset falls back to ATRV`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 20, atLatencyMs = 2)
        exchanger.replies["1003"] = listOf("7E8 02 50 03")
        exchanger.replies["22E003"] = listOf("7E8 04 62 E0 03 40")
        val cache = AtStateCache()

        val scheduler = PidScheduler(exchanger, cache, clock())
        scheduler.submit(
            listOf(
                command(mode = "22", pid = "E003", freq = 3600.0, din = "03")
                    .pollEntry(Priority.BACKGROUND),
            ),
        )
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(10_000)
        assertTrue(exchanger.countOf("3E00") > 0, "the session should have been held awake")

        // ElmSession recovers the adapter and drops what it can no longer vouch for.
        val heldWhileOpen = exchanger.countOf("3E00")
        cache.ecuSession.clear()
        advanceTimeBy(30_000)

        exchanger.countOf("3E00") shouldBe heldWhileOpen
        assertTrue(
            exchanger.countOf("ATRV") >= 3,
            "with no session left to hold, idle time goes back to the cheap keep-alive",
        )
    }

    @Test
    fun `an open diagnostic session is held with tester-present instead`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 20, atLatencyMs = 2)
        exchanger.replies["1003"] = listOf("7E8 02 50 03")
        exchanger.replies["22E003"] = listOf("7E8 04 62 E0 03 40")

        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
        scheduler.submit(
            listOf(
                command(mode = "22", pid = "E003", freq = 3600.0, din = "03")
                    .pollEntry(Priority.BACKGROUND),
            ),
        )
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(30_000)

        // The session was entered for the command, and nothing has closed it.
        exchanger.countOf("1003") shouldBe 1

        val testerPresent = exchanger.countOf("3E00")
        assertTrue(testerPresent >= 5, "an open session must be held awake; sent $testerPresent tester-presents")
        exchanger.countOf("ATRV") shouldBe 0
    }
}
