package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.obd.session.AtStateCache
import io.kotest.matchers.doubles.shouldBeGreaterThan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What happens after the adapter goes away for five seconds — which it does, every time the
 * car searches for a protocol, or the BLE link stalls behind a phone call.
 *
 * The wrong answer is to catch up. Five missed one-second cycles are five requests that the
 * adapter now owes us, and firing them back to back spends the entire budget of a 15 q/s
 * clone re-reading values that are already five seconds stale — while the *current* value,
 * the one on the gauge, waits behind them. Missed cycles are missed. Skip them.
 */
class PollLatenessTest {

    private fun TestScope.clock() = testPollClock()

    @Test
    fun `a five second stall does not become five catch-up rounds`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 10, atLatencyMs = 1)
        exchanger.replies["010C"] = listOf(RPM_FRAME)

        var polls = 0
        exchanger.beforeExchange = { request ->
            if (request.ascii == "010C" && ++polls == 2) delay(5_000)
        }

        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
        scheduler.submit(listOf(command(pid = "0C", freq = 1.0).pollEntry(Priority.CRITICAL)))
        backgroundScope.launch { scheduler.run() }

        // t=0 first poll, t=1000 second poll — which hangs until t=6010.
        advanceTimeBy(6_500)

        // Cycles at 2000, 3000, 4000, 5000 and 6000 were missed while the adapter was gone.
        // The only correct number of requests here is one: the one that is due *now*.
        assertEquals(
            3,
            exchanger.countOf("010C"),
            "two polls before the stall plus one after it — not five catch-up rounds",
        )
        scheduler.health.value.dropRatePct shouldBeGreaterThan 0.0
    }
}
