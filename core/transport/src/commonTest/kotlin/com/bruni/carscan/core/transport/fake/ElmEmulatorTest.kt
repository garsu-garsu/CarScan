package com.bruni.carscan.core.transport.fake

import com.bruni.carscan.core.transport.TestElmClient
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ElmEmulatorTest {

    // --- helpers ------------------------------------------------------------

    private fun TestScope.virtualClock() = ElmClock { testScheduler.currentTime }

    private fun fixed(
        rpm: Double = 800.0,
        speedKph: Double = 0.0,
        coolantC: Double = 90.0,
        throttlePct: Double = 0.0,
        fuelPct: Double = 60.0,
        socPct: Double = 55.5,
    ) = VehicleStateSource {
        VehicleState(DrivingPhase.IDLE, rpm, speedKph, coolantC, throttlePct, fuelPct, socPct)
    }

    /** Strips the CAN id and reassembles ISO-TP frames back into one payload. */
    private fun reassemble(lines: List<String>): List<Int> {
        val frames = lines.map { line -> line.split(' ').drop(1).map { it.toInt(16) } }
        val first = frames.first()
        return if ((first[0] and 0xF0) == 0x00) {
            first.drop(1).take(first[0])
        } else {
            val length = ((first[0] and 0x0F) shl 8) or first[1]
            val payload = first.drop(2).toMutableList()
            frames.drop(1).forEach { payload += it.drop(1) }
            payload.take(length)
        }
    }

    private fun List<Int>.hex() = joinToString(" ") { it.toString(16).uppercase().padStart(2, '0') }

    /** The AT preamble every consumer sends: echo off, headers on. */
    private suspend fun TestElmClient.initHeadersOn() {
        exchange("ATE0")
        exchange("ATH1")
    }

    // --- AT ladder ----------------------------------------------------------

    @Test
    fun `the adapter echoes until ATE0 and stops afterwards`() = runTest {
        val elm = ElmEmulator(clock = virtualClock())
        elm.open()
        val client = TestElmClient(elm, backgroundScope)

        // The echo of ATE0 itself is still returned — echo is disabled only once the
        // command has been processed. This is what a real ELM327 does.
        client.exchangeLines("ATE0") shouldContainExactly listOf("ATE0", "OK")
        client.exchangeLines("ATL0") shouldContainExactly listOf("OK")
    }

    @Test
    fun `without ATE0 the echo line is there`() = runTest {
        val elm = ElmEmulator(clock = virtualClock())
        elm.open()
        val client = TestElmClient(elm, backgroundScope)

        client.exchangeLines("ATI") shouldContainExactly listOf("ATI", "ELM327 v1.5")
    }

    @Test
    fun `an unknown AT command answers with a question mark`() = runTest {
        val elm = ElmEmulator(clock = virtualClock())
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.exchange("ATE0")

        client.exchangeLines("ATQQ") shouldContainExactly listOf("?")
    }

    @Test
    fun `ATZ settles, then resets the AT state`() = runTest {
        val elm = ElmEmulator(
            clock = virtualClock(),
            config = ElmEmulatorConfig(resetSettleTime = 1.seconds, commandLatency = Duration.ZERO),
        )
        elm.open()
        val client = TestElmClient(elm, backgroundScope)

        client.exchange("ATE0")
        client.exchange("ATH1")
        elm.atState.echo shouldBe false
        elm.atState.headers shouldBe true

        val before = testScheduler.currentTime
        client.exchangeLines("ATZ") shouldContainExactly listOf("ELM327 v1.5")
        testScheduler.currentTime - before shouldBe 1_000L

        elm.atState.echo shouldBe true
        elm.atState.headers shouldBe false
        // and the echo really is back on the wire
        client.exchangeLines("ATI") shouldContainExactly listOf("ATI", "ELM327 v1.5")
    }

    @Test
    fun `the whole init ladder is answered`() = runTest {
        val elm = ElmEmulator(clock = virtualClock())
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.exchange("ATE0")

        listOf(
            "ATL0", "ATS0", "ATH1", "ATCAF1", "ATSP0", "ATSH 7E4", "ATCRA 7EC",
            "ATCEA", "ATFCSH 7E4", "ATFCSD 300000", "ATFCSM1", "ATST 32", "ATAT1",
        ).forEach { command ->
            client.exchangeLines(command) shouldContainExactly listOf("OK")
        }
        client.exchangeLines("ATRV") shouldContainExactly listOf("12.6V")
        client.exchangeLines("ATDPN") shouldContainExactly listOf("A6")

        client.exchangeLines("ATSP6") shouldContainExactly listOf("OK")
        client.exchangeLines("ATDPN") shouldContainExactly listOf("6")
    }

    // --- AT state is honoured -----------------------------------------------

    @Test
    fun `ATH1 prefixes the CAN id and ATH0 does not`() = runTest {
        val elm = ElmEmulator(clock = virtualClock(), vehicle = fixed(rpm = 1726.0))
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.exchange("ATE0")

        client.exchange("ATH1")
        client.exchangeLines("010C") shouldContainExactly listOf("7E8 04 41 0C 1A F8")

        client.exchange("ATH0")
        client.exchangeLines("010C") shouldContainExactly listOf("04 41 0C 1A F8")
    }

    @Test
    fun `ATS0 strips the spaces`() = runTest {
        val elm = ElmEmulator(clock = virtualClock(), vehicle = fixed(rpm = 1726.0))
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.initHeadersOn()

        client.exchange("ATS0")
        client.exchangeLines("010C") shouldContainExactly listOf("7E804410C1AF8")
    }

    @Test
    fun `an ATCRA filter that excludes the answering ECU produces NO DATA`() = runTest {
        val elm = ElmEmulator(clock = virtualClock(), vehicle = fixed(rpm = 1726.0))
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.initHeadersOn()

        client.exchange("ATCRA 7E8")
        client.exchangeLines("010C") shouldContainExactly listOf("7E8 04 41 0C 1A F8")

        client.exchange("ATCRA 7EC")
        client.exchangeLines("010C") shouldContainExactly listOf("NO DATA")

        // clearing the filter brings it back
        client.exchange("ATCRA")
        client.exchangeLines("010C") shouldContainExactly listOf("7E8 04 41 0C 1A F8")
    }

    @Test
    fun `a request sent to an ECU that does not own the service yields NO DATA`() = runTest {
        val elm = ElmEmulator(clock = virtualClock())
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.initHeadersOn()

        // mode 22 0101 is the Kia BMS. Asking the engine ECU for it gets nothing.
        client.exchangeLines("220101") shouldContainExactly listOf("NO DATA")
    }

    // --- OBD payloads -------------------------------------------------------

    @Test
    fun `010C is the 1726 rpm anchor`() = runTest {
        val elm = ElmEmulator(clock = virtualClock(), vehicle = fixed(rpm = 1726.0))
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.initHeadersOn()

        val lines = client.exchangeLines("010C")
        lines shouldContainExactly listOf("7E8 04 41 0C 1A F8")

        // ((0x1A * 256) + 0xF8) / 4 == 1726
        val payload = reassemble(lines)
        payload.hex() shouldBe "41 0C 1A F8"
        (((payload[2] * 256) + payload[3]) / 4.0) shouldBe (1726.0 plusOrMinus 1e-9)
    }

    @Test
    fun `mode 01 answers speed, coolant, throttle and fuel from the vehicle state`() = runTest {
        val elm = ElmEmulator(
            clock = virtualClock(),
            vehicle = fixed(speedKph = 60.0, coolantC = 90.0, throttlePct = 20.0, fuelPct = 50.0),
        )
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.initHeadersOn()

        reassemble(client.exchangeLines("010D")).hex() shouldBe "41 0D 3C"      // 0x3C = 60 km/h
        reassemble(client.exchangeLines("0105")).hex() shouldBe "41 05 82"      // 0x82 - 40 = 90 C
        reassemble(client.exchangeLines("0111")).hex() shouldBe "41 11 33"      // 0x33 = 51 -> 20 %
        reassemble(client.exchangeLines("012F")).hex() shouldBe "41 2F 80"      // 0x80 = 128 -> 50 %
    }

    @Test
    fun `0100 reports the supported PID bitmask`() = runTest {
        val elm = ElmEmulator(clock = virtualClock())
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.initHeadersOn()

        // bits: 01, 05, 0C, 0D, 11 supported; bit 32 set => PIDs 21-40 also described
        reassemble(client.exchangeLines("0100")).hex() shouldBe "41 00 88 18 80 01"
    }

    @Test
    fun `0101 reports MIL state and stored DTC count`() = runTest {
        val elm = ElmEmulator(
            clock = virtualClock(),
            config = ElmEmulatorConfig(milOn = true, dtcCount = 3),
        )
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.initHeadersOn()

        reassemble(client.exchangeLines("0101")).take(3).hex() shouldBe "41 01 83"
    }

    // --- ISO-TP -------------------------------------------------------------

    @Test
    fun `mode 09 VIN comes back as a well formed multi frame response`() = runTest {
        val elm = ElmEmulator(
            clock = virtualClock(),
            config = ElmEmulatorConfig(vin = "KMHL14JA1LA123456"),
        )
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.initHeadersOn()

        val lines = client.exchangeLines("0902")
        lines.size shouldBe 3
        lines[0] shouldBe "7E8 10 14 49 02 01 4B 4D 48"   // FF: length 0x014 = 20 bytes
        lines[1].startsWith("7E8 21 ") shouldBe true      // CF, sequence 1
        lines[2].startsWith("7E8 22 ") shouldBe true      // CF, sequence 2

        val payload = reassemble(lines)
        payload.size shouldBe 20
        payload.take(3).hex() shouldBe "49 02 01"
        payload.drop(3).map { it.toChar() }.joinToString("") shouldBe "KMHL14JA1LA123456"
    }

    @Test
    fun `Kia 220101 on hdr 7E4 rax 7EC reassembles to the 55 percent SOC anchor`() = runTest {
        val elm = ElmEmulator(clock = virtualClock(), vehicle = fixed(socPct = 55.5))
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.initHeadersOn()
        client.exchange("ATSH 7E4")
        client.exchange("ATCRA 7EC")

        val lines = client.exchangeLines("220101")
        lines shouldContainExactly listOf(
            "7EC 10 09 62 01 01 EF FB E7",
            "7EC 21 EF 6F 00 00 00 00 00",
        )

        val payload = reassemble(lines)
        payload.hex() shouldBe "62 01 01 EF FB E7 EF 6F 00"

        // EV6_HVBAT_SOC: bix 32, len 8, mul 0.5 -> byte 4 of the post-echo payload
        val soc = payload.drop(3)[4] * 0.5
        soc shouldBe (55.5 plusOrMinus 1e-9)
    }

    // --- clone behaviour ----------------------------------------------------

    @Test
    fun `the throughput ceiling paces exchanges on virtual time`() = runTest {
        val elm = ElmEmulator(
            clock = virtualClock(),
            config = ElmEmulatorConfig(
                commandLatency = Duration.ZERO,
                maxExchangesPerSecond = 15, // 1000 / 15 = 66 ms between exchanges
            ),
        )
        elm.open()
        val client = TestElmClient(elm, backgroundScope)

        val start = testScheduler.currentTime
        repeat(5) { client.exchange("010C") }
        // The first exchange is free; the next four each wait out the 66 ms budget.
        testScheduler.currentTime - start shouldBe 4 * 66L
    }

    @Test
    fun `expectedFrames is honoured, and a clone that lacks it answers with a question mark`() = runTest {
        val good = ElmEmulator(clock = virtualClock(), vehicle = fixed(rpm = 1726.0))
        good.open()
        val goodClient = TestElmClient(good, backgroundScope)
        goodClient.initHeadersOn()
        goodClient.exchangeLines("010C1") shouldContainExactly listOf("7E8 04 41 0C 1A F8")

        val clone = ElmEmulator(
            clock = virtualClock(),
            config = ElmEmulatorConfig(supportsExpectedFrames = false),
            vehicle = fixed(rpm = 1726.0),
        )
        clone.open()
        val cloneClient = TestElmClient(clone, backgroundScope)
        cloneClient.initHeadersOn()
        cloneClient.exchangeLines("010C1") shouldContainExactly listOf("?")
    }

    @Test
    fun `the emulator can be forced to chunk one byte at a time`() = runTest {
        val elm = ElmEmulator(
            clock = virtualClock(),
            config = ElmEmulatorConfig(chunkSize = 1, commandLatency = 5.milliseconds),
            vehicle = fixed(rpm = 1726.0),
        )
        elm.open()
        val client = TestElmClient(elm, backgroundScope)
        client.initHeadersOn()

        client.exchangeLines("010C") shouldContainExactly listOf("7E8 04 41 0C 1A F8")
    }
}
