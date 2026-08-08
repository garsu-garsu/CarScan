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
 * The frame-count suffix, and the clone that has never heard of it.
 *
 * `010C1` tells the adapter to hand the answer back the moment it has one frame, instead of
 * sitting out its whole ATST timeout waiting for a second one that is never coming. It is
 * worth roughly 2x on a clone. It is also the kind of thing a clone answers `?` to — and
 * the `?` must be read as *"this adapter does not do frame counts"*, never as *"this car
 * does not have this PID"*. Confusing the two would drop working commands off the plan.
 */
class PollExpectedFramesTest {

    private fun TestScope.clock() = testPollClock()

    private val socCommand = command(
        hdr = "7E4",
        rax = "7EC",
        mode = "22",
        pid = "0101",
        freq = 0.1,
        signals = listOf(signal("SOC", com.bruni.carscan.core.model.ObdUnit.PERCENT, len = 8, bix = 32)),
    )

    /** A two-frame ISO-TP answer, as mode 22 gives. */
    private val socReply = listOf("7EC 10 09 62 01 01 EF FB", "7EC 21 E7 EF 6F 00 00 00 00")

    @Test
    fun `the frame count is learned from the first answer and sent from then on`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 20)
        exchanger.replies["220101"] = socReply

        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
        scheduler.submit(listOf(socCommand.pollEntry(Priority.CRITICAL)))
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(2_000)

        val obd = exchanger.obdRequests
        // Nothing is known about the shape of the answer until the answer arrives, and a
        // frame count that is too low truncates a multi-frame reply into a plausible lie.
        obd.first().expectedFrames shouldBe null
        obd.last().expectedFrames shouldBe 2
    }

    @Test
    fun `a question mark to the frame count disables it for the adapter, not the command`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 20)
        exchanger.replies["220101"] = socReply
        exchanger.rejectsExpectedFrames = true

        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
        scheduler.submit(listOf(socCommand.pollEntry(Priority.CRITICAL)))
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(2_000)

        // The `?` was about the suffix. The command itself works, and must still be polled.
        scheduler.unsupported.value shouldBe emptySet()
        val tail = exchanger.obdRequests.takeLast(5)
        assertTrue(tail.size == 5, "polling must continue after the capability is disabled")
        assertTrue(
            tail.all { it.expectedFrames == null },
            "the suffix must never be sent again to this adapter, got ${tail.map { it.expectedFrames }}",
        )
    }
}
