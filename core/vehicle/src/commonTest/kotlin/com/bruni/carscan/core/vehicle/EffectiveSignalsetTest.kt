package com.bruni.carscan.core.vehicle

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.model.obdb.spec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class EffectiveSignalsetTest {

    private val sae by lazy { obdbSignalset(SAE_J1979) }
    private val ev6 by lazy { obdbSignalset(KIA_EV6) }
    private val ford by lazy { obdbSignalset(FORD_F150) }

    private fun ev6(year: Int) = EffectiveSignalset.of(sae, ev6, year)
    private fun ford(year: Int) = EffectiveSignalset.of(sae, ford, year)

    private fun EffectiveSignalset.requests() = commands.map { it.command.spec().request }

    // ---- the union ---------------------------------------------------------------------

    @Test
    fun `EV6 2023 carries BOTH the standard mode-01 PIDs AND the vehicle mode-22 commands`() {
        val effective = ev6(2023)

        // Vehicle repos hold no mode-01 PIDs at all; without SAEJ1979 there is no RPM gauge.
        effective.requests() shouldContainAll listOf("010C", "010D", "0105") // RPM, VSS, ECT
        effective.sourcesOf("RPM").single().signal.name shouldBe "Engine RPM"
        effective.sourcesOf("VSS").single().command.command.spec().mode shouldBe 0x01

        // ...and the EV6's own mode-22 commands are there too.
        effective.requests() shouldContain "220101"
        val soc = effective.sourcesOf("EV6_HVBAT_SOC").single()
        soc.command.command.spec().mode shouldBe 0x22
        soc.command.command.hdr shouldBe "7E4"
        soc.signal.suggestedMetric shouldBe SuggestedMetric.STATE_OF_CHARGE

        effective.commands.size shouldBe sae.commands.size + ev6.commands.size // 103 + 13
        effective.modelYear shouldBe 2023
    }

    @Test
    fun `the standard PIDs are the same for every vehicle`() {
        val standardRequests = sae.commands.map { it.spec().request }
        ev6(2023).requests() shouldContainAll standardRequests
        ford(2013).requests() shouldContainAll standardRequests
    }

    // ---- year filtering ----------------------------------------------------------------

    @Test
    fun `a from-2009 command is absent on a 2002 F-150 and present on a 2013`() {
        // 22404C carries filter {from: 2009}.
        ford(2002).requests() shouldNotContain "22404C"
        ford(2013).requests() shouldContain "22404C"

        // Only 9 of the F-150's 116 commands apply to a 2002; all 116 apply to a 2013.
        ford(2002).commands.size shouldBe sae.commands.size + 9
        ford(2013).commands.size shouldBe sae.commands.size + 116
    }

    // ---- the inverted range: a hole in the middle, not an empty set ---------------------

    @Test
    fun `Ford 226185's inverted dbgfilter flags it experimental in 2002 and 2013 but not 2007`() {
        // filter {to: 2015} keeps the command in all three years, so the ONLY thing that
        // varies is the inverted dbgfilter {to: 2003, from: 2011, years: [2005, 2006, 2009]}.
        // Read as a normal range, from(2011) >= to(2003) is an empty set and this command
        // would never be experimental. It is inverted: 2003-and-earlier OR 2011-and-later.
        fun fli(year: Int) = ford(year).commands.single { it.command.spec().request == "226185" }

        fli(2002).experimental shouldBe true // <= 2003
        fli(2013).experimental shouldBe true // >= 2011
        fli(2007).experimental shouldBe false // the hole in the middle
        fli(2005).experimental shouldBe true // the explicit `years` list is OR'd on top
    }

    @Test
    fun `an inverted year filter includes 2002 and 2013 and excludes 2007`() {
        // No OBDb repo currently puts an inverted range in `filter` (only in `dbgfilter`), so
        // the INCLUSION side of the rule has no real fixture. It is still live code, and a
        // "simplification" of matches() to `year in from..to` would silently drop the command
        // for every year. Pin it here.
        val vehicle = SignalsetParser.parse(
            """
            {"commands":[{
              "hdr":"720","cmd":{"22":"9001"},"freq":5,
              "filter":{"to":2003,"from":2011},
              "signals":[{"id":"INVERTED","name":"Inverted","fmt":{"len":8}}]
            }]}
            """.trimIndent(),
        )
        fun requests(year: Int) = EffectiveSignalset.of(sae, vehicle, year).requests()

        requests(2002) shouldContain "229001"
        requests(2013) shouldContain "229001"
        requests(2007) shouldNotContain "229001"
    }

    // ---- experimental flagging ---------------------------------------------------------

    @Test
    fun `a command with no dbgfilter is never experimental`() {
        // The trap: YearFilter?.matches(year) returns TRUE for a null filter, because for
        // `filter` null means "every year". Applying it to `dbgfilter` unguarded marks every
        // command in SAEJ1979 — all 103 of which have no dbgfilter — as unverified.
        val effective = ev6(2023)
        val standard = effective.commands.filter { it.command.dbgfilter == null }

        standard.size shouldBe sae.commands.size
        standard.count { it.experimental } shouldBe 0
        effective.sourcesOf("RPM").single().command.experimental shouldBe false
    }

    @Test
    fun `dbg true is experimental regardless of year`() {
        val vehicle = SignalsetParser.parse(
            """
            {"commands":[{"hdr":"720","cmd":{"22":"9002"},"freq":5,"dbg":true,
              "signals":[{"id":"UNVERIFIED","name":"Unverified","fmt":{"len":8}}]}]}
            """.trimIndent(),
        )
        EffectiveSignalset.of(sae, vehicle, 2013)
            .sourcesOf("UNVERIFIED").single().command.experimental shouldBe true
    }

    @Test
    fun `every EV6 command is experimental in 2021 and none of them are in 2023`() {
        // All 13 EV6 commands carry dbgfilter {to: 2021, from: 2025} — again inverted.
        fun vehicleCommands(year: Int) =
            ev6(year).commands.filter { it.command.spec().mode == 0x22 }

        vehicleCommands(2021).let { commands ->
            commands.size shouldBe 13
            commands.count { it.experimental } shouldBe 13
        }
        vehicleCommands(2023).let { commands ->
            commands.size shouldBe 13
            commands.count { it.experimental } shouldBe 0
        }
    }

    // ---- the index ---------------------------------------------------------------------

    @Test
    fun `a metric-only index cannot address engine RPM, so signals are indexed by id too`() {
        val effective = ev6(2023)

        // RPM has no suggestedMetric. Every signal is addressable by id regardless.
        effective[MetricKey.Signal("RPM")].single().signal.suggestedMetric shouldBe null
        effective[MetricKey.Signal("EV6_HVBAT_SOC")].single().signal.id shouldBe "EV6_HVBAT_SOC"

        effective.allSignalCount() shouldBe sae.allSignals().size + ev6.allSignals().size
    }

    @Test
    fun `one metric can be produced by several signals, and none of them are lost`() {
        val soc = ev6(2023).sourcesOf(SuggestedMetric.STATE_OF_CHARGE)

        // The EV6 reports state of charge three different ways, and SAEJ1979 adds a fourth.
        soc.map { it.signal.id } shouldContainAll
            listOf("EV6_HVBAT_SOC", "EV6_HVBAT_SOC_DISP", "EV6_HVBAT_SOC_VCMS")
        soc.size shouldBe 4
        soc.forEach { it.signal.suggestedMetric shouldBe SuggestedMetric.STATE_OF_CHARGE }
    }

    @Test
    fun `a signal id repeated across commands keeps every producer`() {
        // SAEJ1979 defines SHRTFT11 on two different commands (0106 and 0134-family).
        val sources = ev6(2023).sourcesOf("SHRTFT11")
        sources.size shouldBe 2
        sources.map { it.command.command.spec().request }.toSet().size shouldBe 2
    }

    @Test
    fun `the index only contains commands that survived the year filter`() {
        // F150_FLI_ALT rides on 226185, filter {to: 2015}.
        ford(2013).sourcesOf("F150_FLI_ALT").size shouldBe 1
        ford(2020).sourcesOf("F150_FLI_ALT").size shouldBe 0
    }

    @Test
    fun `an unknown key resolves to nothing rather than throwing`() {
        ev6(2023).sourcesOf("NOT_A_SIGNAL").size shouldBe 0
        ev6(2023).sourcesOf(SuggestedMetric.CVT_DETERIORATION).size shouldBe 0
    }
}

private fun EffectiveSignalset.allSignalCount() = commands.sumOf { it.command.signals.size }
