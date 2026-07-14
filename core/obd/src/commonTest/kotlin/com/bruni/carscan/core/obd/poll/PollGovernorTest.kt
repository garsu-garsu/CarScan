package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.obd.session.AtStateCache
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The governor, against the adapter that actually exists.
 *
 * A 66 ms round trip is a 15-queries-per-second adapter, which is what a €5 clone is. Ten
 * gauges at 10 Hz want 100. The scheduler cannot deliver that and neither can any other
 * scheduler, so what is under test is that it fails *honestly*: it slows the things nobody
 * is looking at, it never lets the backlog grow, and [PollerHealth] reports the shortfall
 * rather than hiding it.
 */
class PollGovernorTest {

    private fun TestScope.clock() = PollClock { testScheduler.currentTime }

    /** ~15 queries per second, the way a real one is: every round trip costs 66 ms. */
    private fun cloneAdapter() = FakeExchanger(obdLatencyMs = 66, atLatencyMs = 2)

    @Test
    fun `ten critical gauges against a 15 q per s adapter - background stretched, critical capped, health honest`() =
        runTest {
            val exchanger = cloneAdapter()
            val critical = (0 until 10).map { i ->
                val pid = (0x10 + i).toString(16).uppercase().padStart(2, '0')
                exchanger.replies["01$pid"] = listOf("7E8 03 41 $pid 40")
                command(pid = pid, freq = 0.1, signals = listOf(signal("S$i"))).pollEntry(Priority.CRITICAL)
            }
            exchanger.replies["0131"] = listOf("7E8 04 41 31 12 34")
            exchanger.replies["0121"] = listOf("7E8 04 41 21 00 10")
            val background = listOf(
                command(pid = "31", freq = 1.0, signals = listOf(signal("B1"))).pollEntry(Priority.BACKGROUND),
                command(pid = "21", freq = 1.0, signals = listOf(signal("B2"))).pollEntry(Priority.BACKGROUND),
            )

            val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
            scheduler.submit(critical + background)
            backgroundScope.launch { scheduler.run() }
            advanceTimeBy(10_000)

            val health = scheduler.health.value

            // What the adapter can do, and what we are asking of it. Both must be true numbers:
            // this is the pair the UI turns into "your adapter: 15 queries/sec — showing 10
            // tiles at 1.4 Hz".
            assertTrue(
                health.capacityHz in 13.0..17.0,
                "a 66 ms round trip is ~15 q/s; health says ${health.capacityHz}",
            )
            assertTrue(
                health.loadHz >= 100.0,
                "10 gauges at 10 Hz plus 2 at 1 Hz is 102 Hz of demand; health says ${health.loadHz}",
            )
            health.dropRatePct shouldBeGreaterThan 0.0
            health.stretchedCount shouldBeGreaterThanOrEqual 1

            // The backlog does not grow. Everything the scheduler managed to do fits inside
            // what the adapter could physically answer in 10 seconds.
            val exchanges = exchanger.obdRequests.size
            assertTrue(exchanges <= 170, "a 15 q/s adapter cannot answer more than ~150 in 10 s, made $exchanges")

            // BACKGROUND is what pays. Declared at 1 Hz, it must have been stretched well below
            // the 10 polls that 10 seconds would otherwise buy it.
            val backgroundPolls = exchanger.countOf("0131") + exchanger.countOf("0121")
            assertTrue(backgroundPolls <= 10, "background must be stretched under load, got $backgroundPolls polls")

            // CRITICAL is capped, not starved: every visible gauge still gets read.
            val criticalPolls = (0 until 10).map { i ->
                exchanger.countOf("01" + (0x10 + i).toString(16).uppercase().padStart(2, '0'))
            }
            assertTrue(
                criticalPolls.all { it >= 5 },
                "every critical gauge must keep updating; per-gauge polls were $criticalPolls",
            )
            assertTrue(
                criticalPolls.sum() >= 100,
                "critical work should consume the adapter's budget, got ${criticalPolls.sum()}",
            )
        }

    @Test
    fun `setVisible promotes a command out of the governor's reach`() = runTest {
        val exchanger = cloneAdapter()
        exchanger.replies["010C"] = listOf(RPM_FRAME)
        exchanger.replies["010D"] = listOf(SPEED_FRAME)

        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
        scheduler.submit(
            listOf(
                command(pid = "0C", freq = 0.1, signals = listOf(signal("RPM"))).pollEntry(Priority.BACKGROUND),
                command(pid = "0D", freq = 0.1, signals = listOf(signal("SPEED"))).pollEntry(Priority.BACKGROUND),
            ),
        )
        // The user is looking at the RPM tile. 20 Hz of demand against 15 q/s of adapter means
        // one of these two has to give, and it must be the one that is off screen.
        scheduler.setVisible(setOf(com.bruni.carscan.core.model.MetricKey.Signal("RPM")))
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(10_000)

        val visible = exchanger.countOf("010C")
        val hidden = exchanger.countOf("010D")
        assertTrue(
            visible > hidden * 3,
            "the visible gauge must get the budget: RPM $visible polls vs off-screen SPEED $hidden",
        )
    }
}
