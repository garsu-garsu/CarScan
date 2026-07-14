package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.obd.session.AtStateCache
import com.bruni.carscan.core.model.obdb.spec
import com.bruni.carscan.core.obd.ElmRequest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * AT-affinity batching — the cheapest 4x in the whole app.
 *
 * Six commands split across two ECUs. Polled in the order they appear in the signalset,
 * every one of them needs a different header from the last, so the adapter is sent an
 * `ATSH` (and an `ATCRA`) before every single PID: 18 exchanges to read 6 values. Grouped
 * by ECU it is 6 PIDs plus 2 headers plus 2 filters — and on an adapter that manages 15
 * exchanges a second, that difference *is* the frame rate of the dashboard.
 */
class PollAffinityTest {

    private fun TestScope.clock() = PollClock { testScheduler.currentTime }

    /** The requests up to and including the [n]-th OBD request — i.e. one full round. */
    private fun List<ElmRequest>.round(n: Int): List<ElmRequest> {
        var obd = 0
        val out = mutableListOf<ElmRequest>()
        for (request in this) {
            out += request
            if (!request.ascii.startsWith("AT")) {
                obd++
                if (obd == n) break
            }
        }
        return out
    }

    @Test
    fun `six commands across two headers cost exactly two ATSH per round`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 10, atLatencyMs = 1)

        // Interleaved, the way a signalset lists them: engine, battery, engine, battery…
        val commands = listOf(
            command(hdr = "7E0", rax = "7E8", mode = "01", pid = "0C", freq = 0.2),
            command(hdr = "7E4", rax = "7EC", mode = "22", pid = "0101", freq = 0.2),
            command(hdr = "7E0", rax = "7E8", mode = "01", pid = "05", freq = 0.2),
            command(hdr = "7E4", rax = "7EC", mode = "22", pid = "0102", freq = 0.2),
            command(hdr = "7E0", rax = "7E8", mode = "01", pid = "0D", freq = 0.2),
            command(hdr = "7E4", rax = "7EC", mode = "22", pid = "0103", freq = 0.2),
        )
        commands.forEach { exchanger.replies[it.spec().request] = listOf("7E8 03 41 0C 50") }

        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
        scheduler.submit(commands.map { it.pollEntry(Priority.CRITICAL) })
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(150)

        val round = exchanger.requests.round(6)
        round.count { it.ascii.startsWith("ATSH") } shouldBe 2
        round.count { it.ascii.startsWith("ATCRA") } shouldBe 2

        // …and all six values were actually read, so the 2 is not the 2 of a starved poller.
        round.count { !it.ascii.startsWith("AT") } shouldBe 6
    }
}
