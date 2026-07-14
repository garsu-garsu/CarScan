package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.obd.session.AtStateCache
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Failure memory: the car does not have that PID, and it will not have it tomorrow either.
 *
 * A signalset is a superset — it describes every trim and every model year, and a given car
 * answers `NO DATA` to a good fraction of it. Re-asking a question that has been answered
 * "no" five times is not resilience, it is spending the scarcest resource in the system on
 * nothing. So the command is dropped, and the fact is exposed for the repository to persist,
 * because re-probing it once per session is a wasted round trip once per session, forever.
 */
class PollFailureMemoryTest {

    private fun TestScope.clock() = PollClock { testScheduler.currentTime }

    @Test
    fun `five consecutive NO_DATA drops the command permanently and reports it`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 10, atLatencyMs = 1)
        exchanger.replies["010C"] = listOf(RPM_FRAME)   // healthy
        // "0142" has no reply, so FakeExchanger answers NO DATA — the car does not have it.

        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
        scheduler.submit(
            listOf(
                command(pid = "0C", freq = 0.5).pollEntry(Priority.NORMAL),
                command(pid = "42", freq = 0.2).pollEntry(Priority.NORMAL),
            ),
        )
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(60_000)

        // Backoff x1, x4, x16 stretches the five attempts across ~7 s; after the fifth the
        // command is gone for the rest of the session, so a full minute buys no more.
        assertEquals(
            5,
            exchanger.countOf("0142"),
            "an unsupported PID is asked five times and never again",
        )
        scheduler.unsupported.value shouldBe setOf("7E0.0142")

        // …and the healthy command carried on the whole time.
        assertTrue(exchanger.countOf("010C") > 100, "the rest of the plan must keep polling")
    }

    @Test
    fun `a command that recovers is not dropped`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 10, atLatencyMs = 1)
        exchanger.replies["010C"] = listOf(RPM_FRAME)
        // Four NO_DATA in a row, then it starts answering: an ECU that was still waking up.
        exchanger.faults["010C"] = MutableList(4) { com.bruni.carscan.core.obd.ElmErrorKind.NO_DATA }

        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
        scheduler.submit(listOf(command(pid = "0C", freq = 0.5).pollEntry(Priority.NORMAL)))
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(60_000)

        scheduler.unsupported.value shouldBe emptySet()
        assertTrue(
            exchanger.countOf("010C") > 20,
            "the counter resets on success; polling must resume at the declared rate",
        )
    }
}
