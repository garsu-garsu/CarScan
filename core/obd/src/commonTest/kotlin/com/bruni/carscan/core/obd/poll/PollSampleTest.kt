package com.bruni.carscan.core.obd.poll

import com.bruni.carscan.core.obd.session.AtStateCache
import com.bruni.carscan.core.model.DecodedValue
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SensorSample
import com.bruni.carscan.core.model.SuggestedMetric
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What comes out of the scheduler: decoded samples, in the unit the car reports them in. */
class PollSampleTest {

    private fun TestScope.clock() = testPollClock()

    @Test
    fun `samples carry the native unit, the metric key and the answering ECU`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 10, atLatencyMs = 1)
        exchanger.replies["010C"] = listOf(RPM_FRAME)
        exchanger.replies["010D"] = listOf(SPEED_FRAME)

        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
        val received = mutableListOf<SensorSample>()
        backgroundScope.launch { scheduler.samples.collect { received += it } }
        runCurrent()

        scheduler.submit(
            listOf(
                command(
                    pid = "0C",
                    freq = 0.1,
                    signals = listOf(signal("RPM", ObdUnit.RPM, len = 16, div = 4.0)),
                ).pollEntry(Priority.CRITICAL),
                // `dbg` marks a command OBDb has never verified on any car. It still decodes;
                // it is the UI's job to say so, which it can only do if the flag travels.
                command(
                    pid = "0D",
                    freq = 0.1,
                    dbg = true,
                    signals = listOf(
                        signal("SPEED", ObdUnit.KILOMETERS_PER_HOUR, len = 8, metric = SuggestedMetric.SPEED),
                    ),
                ).pollEntry(Priority.CRITICAL),
            ),
        )
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(500)

        val rpm = received.first { it.signalId == "RPM" }
        // 0x1AF8 = 6904, divided by 4 — the SAE J1979 quarter-RPM encoding.
        rpm.value shouldBe DecodedValue.Numeric(1726.0)
        rpm.unit shouldBe ObdUnit.RPM
        // No OBDb metric covers engine RPM, so the tile addresses it by signal id.
        rpm.key shouldBe MetricKey.Signal("RPM")
        rpm.ecu shouldBe "7E8"
        rpm.experimental shouldBe false

        val speed = received.first { it.signalId == "SPEED" }
        speed.value shouldBe DecodedValue.Numeric(80.0)
        speed.unit shouldBe ObdUnit.KILOMETERS_PER_HOUR
        speed.key shouldBe MetricKey.Metric(SuggestedMetric.SPEED)
        speed.experimental shouldBe true
    }

    @Test
    fun `an answer from an ECU the command did not address is not decoded`() = runTest {
        val exchanger = FakeExchanger(obdLatencyMs = 10, atLatencyMs = 1)
        // 7EB answers the same PID with the same echo — two modules can, and Elantra's do.
        // Only the one this command filtered on may become a reading.
        exchanger.replies["010C"] = listOf("7EB 04 41 0C FF FF", RPM_FRAME)

        val scheduler = PidScheduler(exchanger, AtStateCache(), clock())
        val received = mutableListOf<SensorSample>()
        backgroundScope.launch { scheduler.samples.collect { received += it } }
        runCurrent()

        scheduler.submit(listOf(command(rax = "7E8", pid = "0C", freq = 0.5).pollEntry()))
        backgroundScope.launch { scheduler.run() }
        advanceTimeBy(200)

        assertTrue(received.isNotEmpty(), "the addressed ECU's answer must still decode")
        assertEquals(
            listOf("7E8"), received.map { it.ecu }.distinct(),
            "a reading from an ECU we did not ask is a plausible wrong number, not a bonus",
        )
        received.forEach { it.value shouldBe DecodedValue.Numeric(1726.0) }
    }
}
