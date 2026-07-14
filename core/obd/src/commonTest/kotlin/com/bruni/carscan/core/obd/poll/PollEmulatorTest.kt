package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.obd.session.AtStateCache
import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.obd.ElmRequest
import com.bruni.carscan.core.transport.fake.ElmClock
import com.bruni.carscan.core.transport.fake.ElmEmulator
import com.bruni.carscan.core.transport.fake.ElmEmulatorConfig
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The whole scheduler against an ELM327 that behaves like one — including its ceiling.
 *
 * `maxExchangesPerSecond = 15` is the counterfeit adapter every user with a €5 dongle has.
 * Five gauges at 10 Hz is 50 queries a second of demand against 15 of supply. Nothing here
 * closes that gap. What is under test is that the numbers the user is shown are the real
 * ones, and that the dashboard keeps updating instead of falling behind forever.
 */
class PollEmulatorTest {

    private fun TestScope.pollClock() = testPollClock()

    @Test
    fun `five gauges at 10 Hz on a 15 q per s clone - honest health, bounded backlog, live samples`() = runTest {
        val elm = ElmEmulator(
            clock = ElmClock { testScheduler.currentTime },
            config = ElmEmulatorConfig(
                commandLatency = 20.milliseconds,
                maxExchangesPerSecond = 15,
            ),
        )
        elm.open()
        val exchanger = EmulatorExchanger(elm, backgroundScope, pollClock())

        // The preamble ElmSession sends: no echo, no spaces, headers on. Without ATH1 there
        // is no CAN id on the line and nothing can be attributed to an ECU at all.
        exchanger.exchange(ElmRequest("ATE0"))
        exchanger.exchange(ElmRequest("ATS0"))
        exchanger.exchange(ElmRequest("ATH1"))

        val engine = listOf(
            command(pid = "0C", freq = 0.1, signals = listOf(signal("RPM", ObdUnit.RPM, len = 16, div = 4.0))),
            command(
                pid = "0D", freq = 0.1,
                signals = listOf(signal("SPEED", ObdUnit.KILOMETERS_PER_HOUR, len = 8, metric = SuggestedMetric.SPEED)),
            ),
            command(
                pid = "05", freq = 0.1,
                signals = listOf(
                    signal("COOLANT", ObdUnit.CELSIUS, len = 8, add = -40.0, metric = SuggestedMetric.ENGINE_COOLANT_TEMPERATURE),
                ),
            ),
            command(
                pid = "11", freq = 0.1,
                signals = listOf(signal("THROTTLE", ObdUnit.PERCENT, len = 8, mul = 100.0, div = 255.0)),
            ),
        )
        val battery = command(
            hdr = "7E4", rax = "7EC", mode = "22", pid = "0101", freq = 0.1,
            signals = listOf(
                signal("HVBAT_SOC", ObdUnit.PERCENT, len = 8, bix = 32, div = 2.0, metric = SuggestedMetric.STATE_OF_CHARGE),
            ),
        )
        // A hidden housekeeping poll, declared at 1 Hz. This is what the governor is allowed
        // to sacrifice so the five visible gauges keep moving.
        val housekeeping = command(pid = "2F", freq = 1.0, signals = listOf(signal("FUEL", ObdUnit.PERCENT, len = 8)))

        val scheduler = PidScheduler(exchanger, AtStateCache(), pollClock())
        val samples = mutableListOf<SensorSample>()
        backgroundScope.launch { scheduler.samples.collect { samples += it } }
        runCurrent()

        scheduler.submit(
            (engine + battery).map { it.pollEntry(Priority.CRITICAL) } +
                housekeeping.pollEntry(Priority.BACKGROUND),
        )
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(20_000)

        val health = scheduler.health.value
        val seconds = 20.0
        val achievedHz = samples.size / seconds

        // Where the adapter's 15 exchanges a second actually went. Two ECUs means the header and
        // the receive filter have to be re-sent every time the poller crosses between them, and
        // on this adapter an `ATSH` costs exactly as much as a reading does.
        val at = exchanger.sent.count { it.startsWith("AT") }
        val reads = exchanger.sent.size - at
        println(
            "EMULATOR @15 q/s ceiling, 20 s: capacity=${health.capacityHz.round()} Hz  " +
                "demand=${health.loadHz.round()} Hz  meanRtt=${health.meanRttMs.round()} ms  " +
                "drops=${health.dropRatePct.round()}%  stretched=${health.stretchedCount}  " +
                "exchanges=${exchanger.sent.size} (${reads} reads + $at AT)  " +
                "samples=${samples.size} (${achievedHz.round()} readings/s)",
        )

        // The adapter's real ceiling, measured, not assumed.
        assertTrue(
            health.capacityHz in 8.0..20.0,
            "a 15 q/s emulator must measure as roughly 15 q/s of capacity, got ${health.capacityHz}",
        )
        // 5 gauges x 10 Hz + 1 Hz of housekeeping.
        assertTrue(health.loadHz >= 50.0, "demand should be ~51 Hz, got ${health.loadHz}")
        health.dropRatePct shouldBeGreaterThan 0.0
        health.stretchedCount shouldBeGreaterThanOrEqual 1

        // Live data, decoded from a real ISO-TP exchange with a stateful adapter.
        val rpm = samples.filter { it.signalId == "RPM" }
        rpm.size shouldBeGreaterThanOrEqual 10
        assertTrue(
            rpm.all { (it.value as DecodedValue.Numeric).value > 0.0 },
            "the emulated engine is running; every RPM reading must be positive",
        )
        assertTrue(
            samples.any { it.signalId == "HVBAT_SOC" },
            "the battery ECU is on another header; affinity batching must not starve it",
        )

        // The backlog is bounded: nothing queued up behind the adapter's ceiling.
        assertTrue(
            achievedHz <= 20.0,
            "cannot read faster than the adapter answers; got $achievedHz readings/s",
        )
    }

    private fun Double.round(): String {
        val scaled = kotlin.math.round(this * 10) / 10
        return scaled.toString()
    }
}
