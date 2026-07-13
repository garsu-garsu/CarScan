package com.bruni.carscan.core.vehicle

import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.model.obdb.spec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SignalsetParserTest {

    @Test
    fun `parses a minimal command`() {
        val set = SignalsetParser.parse(
            """
            {"commands":[{
              "hdr":"7E0","cmd":{"01":"0C"},"freq":0.25,
              "signals":[{"id":"RPM","name":"Engine speed","fmt":{"len":16,"div":4,"unit":"rpm"}}]
            }]}
            """.trimIndent(),
        )

        val command = set.commands.single()
        command.hdr shouldBe "7E0"
        command.freq shouldBeExactly 0.25
        command.spec().request shouldBe "010C"

        val signal = command.signals.single()
        signal.id shouldBe "RPM"
        signal.fmt.len shouldBe 16
        signal.fmt.div shouldBeExactly 4.0
        signal.fmt.unit shouldBe ObdUnit.RPM
        // RPM is the most important gauge in the app and OBDb gives it no metric.
        signal.suggestedMetric shouldBe null
    }

    @Test
    fun `applies the documented defaults rather than nulls`() {
        val set = SignalsetParser.parse(
            """
            {"commands":[{"hdr":"7E0","cmd":{"01":"0C"},"freq":1,
              "signals":[{"id":"X","name":"X","fmt":{"len":8}}]}]}
            """.trimIndent(),
        )

        val command = set.commands.single()
        command.fcm1 shouldBe false
        command.dbg shouldBe false
        command.filter shouldBe null
        command.dbgfilter shouldBe null

        val fmt = command.signals.single().fmt
        fmt.bix shouldBe 0
        fmt.mul shouldBeExactly 1.0
        fmt.div shouldBeExactly 1.0
        fmt.add shouldBeExactly 0.0
        fmt.sign shouldBe false
        fmt.blsb shouldBe false

        set.signalGroups.shouldBeEmpty()
        set.synthetics.shouldBeEmpty()
        set.diagnosticLevel shouldBe null
    }

    @Test
    fun `ignores unknown keys so a new OBDb field cannot brick a vehicle`() {
        val set = SignalsetParser.parse(
            """
            {
              "commands":[{
                "hdr":"7E0","cmd":{"01":"0C"},"freq":1,"someFutureCommandField":42,
                "signals":[{"id":"X","name":"X","someFutureSignalField":true,
                            "fmt":{"len":8,"someFutureFmtField":"whatever"}}]
              }],
              "someFutureTopLevelField":{"nested":true}
            }
            """.trimIndent(),
        )

        set.commands.single().signals.single().id shouldBe "X"
    }

    @Test
    fun `an unrecognized unit fails loudly instead of decoding into the wrong quantity`() {
        assertFailsWith<SerializationException> {
            SignalsetParser.parse(
                """
                {"commands":[{"hdr":"7E0","cmd":{"01":"0C"},"freq":1,
                  "signals":[{"id":"X","name":"X","fmt":{"len":8,"unit":"furlongsPerFortnight"}}]}]}
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `an unrecognized suggestedMetric fails loudly instead of being dropped`() {
        assertFailsWith<SerializationException> {
            SignalsetParser.parse(
                """
                {"commands":[{"hdr":"7E0","cmd":{"01":"0C"},"freq":1,
                  "signals":[{"id":"X","name":"X","suggestedMetric":"warpCoreTemperature",
                              "fmt":{"len":8}}]}]}
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `real SAEJ1979 parses without loss`() {
        val set = obdbSignalset(SAE_J1979)

        set.commands.size shouldBe 103
        set.commands.sumOf { it.signals.size } shouldBe 294
        // Every SAEJ1979 command is a mode-01 standard PID; not one of them is mode 22.
        set.commands.map { it.spec().mode }.toSet() shouldBe setOf(0x01)

        val rpm = set.signalsById("RPM").single()
        rpm.fmt.unit shouldBe ObdUnit.RPM
        rpm.suggestedMetric shouldBe null
        set.signalsById("VSS").single().suggestedMetric shouldBe SuggestedMetric.SPEED
        set.signalsById("ECT").single().suggestedMetric shouldBe
            SuggestedMetric.ENGINE_COOLANT_TEMPERATURE
    }

    @Test
    fun `real Kia-EV6 parses without loss`() {
        val set = obdbSignalset(KIA_EV6)

        set.commands.size shouldBe 13
        set.commands.sumOf { it.signals.size } shouldBe 210
        set.commands.map { it.spec().mode }.toSet() shouldBe setOf(0x22)
        // Every EV6 command carries an inverted dbgfilter; none carries a `filter`.
        set.commands.all { it.dbgfilter != null } shouldBe true
        set.commands.all { it.filter == null } shouldBe true
        set.commands.all { it.fcm1 } shouldBe true

        set.signalsById("EV6_HVBAT_SOC").single().suggestedMetric shouldBe
            SuggestedMetric.STATE_OF_CHARGE

        val group = set.signalGroups.single()
        group.id shouldBe "EV6_HVBAT_CMU_VOLT"
        group.matchingRegex shouldBe """EV6_HVBAT_(CMU\d+)_VOLT"""
    }

    @Test
    fun `real Ford-F-150 parses without loss`() {
        val set = obdbSignalset(FORD_F150)

        set.commands.size shouldBe 116
        set.commands.sumOf { it.signals.size } shouldBe 116
        set.commands.map { it.spec().mode }.toSet() shouldBe setOf(0x22)
        set.commands.count { it.filter != null } shouldBe 109
        set.commands.count { it.dbgfilter != null } shouldBe 116

        // The command the inverted-range rule hangs on.
        val fli = set.commands.single { it.spec().request == "226185" }
        fli.filter?.to shouldBe 2015
        fli.dbgfilter?.to shouldBe 2003
        fli.dbgfilter?.from shouldBe 2011
        fli.dbgfilter?.years.orEmpty() shouldContainExactly listOf(2005, 2006, 2009)
    }
}
