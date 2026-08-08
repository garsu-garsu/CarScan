package com.bruni.carscan.core.obd.decode.fixtures

import kotlin.test.Test
import kotlin.test.assertEquals

class FixtureYamlTest {

    @Test
    fun `reads command_id, expected values and the block scalar response`() {
        val f = parseFixtureYaml(
            """
            command_id: 7E4.7EC.220101|fc=1
            test_cases:
            - expected_values:
                EV6_HVBAT_SOC: 55.5
              response: |-
                7EC103E620101EFFBE7
                7EC21EF6F0000000000
            """.trimIndent(),
        )
        assertEquals("7E4.7EC.220101|fc=1", f.commandId)
        assertEquals(1, f.cases.size)
        assertEquals(mapOf("EV6_HVBAT_SOC" to "55.5"), f.cases[0].expected)
        assertEquals(listOf("7EC103E620101EFFBE7", "7EC21EF6F0000000000"), f.cases[0].responseLines)
    }

    @Test
    fun `reads several test cases`() {
        val f = parseFixtureYaml(
            """
            command_id: 7E0.0101
            test_cases:
            - expected_values:
                DTC_CNT: 0
                MIL: 0
              response: |-
                7E80641010007E100
            - expected_values:
                DTC_CNT: 3
              response: |-
                7E80641018307E100
                7E821AABBCC
            """.trimIndent(),
        )
        assertEquals(2, f.cases.size)
        assertEquals(mapOf("DTC_CNT" to "0", "MIL" to "0"), f.cases[0].expected)
        assertEquals(1, f.cases[0].responseLines.size)
        assertEquals(mapOf("DTC_CNT" to "3"), f.cases[1].expected)
        assertEquals(2, f.cases[1].responseLines.size)
    }

    @Test
    fun `quoting is preserved as the difference between a label and a number`() {
        // F150_GEAR is an enumerated signal whose label happens to be "1". DTC_CNT is the
        // number 1. Collapsing both to a number would compare a gear label numerically
        // and pass for the wrong reason.
        val f = parseFixtureYaml(
            """
            command_id: 7E0.7E8.221E12
            test_cases:
            - expected_values:
                F150_GEAR: '1'
                DTC_CNT: 1
                OBDSUP: OBD & OBD II
                SHRTFT1: -20.3125
                ACCEL: 6.3736934462710337e-05
              response: |-
                7E8031E1201
            """.trimIndent(),
        )
        val e = f.cases[0].expected
        assertEquals("1", e["F150_GEAR"])          // quotes stripped, still text
        assertEquals("1", e["DTC_CNT"])
        assertEquals("OBD & OBD II", e["OBDSUP"])  // bare scalar with spaces
        assertEquals("-20.3125", e["SHRTFT1"])
        assertEquals("6.3736934462710337e-05", e["ACCEL"])
    }

    @Test
    fun `a single-frame response is written inline, not as a block`() {
        // Most of mode 01 fits in one CAN frame, and OBDb writes those as a plain scalar.
        // Reading only the `|-` form leaves the transcript empty and every signal in the
        // command silently fails to decode.
        val f = parseFixtureYaml(
            """
            command_id: 7E0.0101
            test_cases:
            - expected_values:
                MIL: 0
              response: 7E80641010007E500
            """.trimIndent(),
        )
        assertEquals(listOf("7E80641010007E500"), f.cases[0].responseLines)
        assertEquals(mapOf("MIL" to "0"), f.cases[0].expected)
    }

    @Test
    fun `a single-quoted response is unquoted, not fed to the parser with its quotes on`() {
        // The fourth spelling, and the most common one in the corpus: OBDb writes most of
        // mode 01 as `response: '7E806410100076500'`. Leaving the quotes on makes every
        // such line non-hex, so parseElmLine rejects it and the whole transcript decodes
        // to nothing — 2145 expected values across 934 files, silently.
        val f = parseFixtureYaml(
            """
            command_id: 7E0.0101
            test_cases:
            - expected_values:
                MIL: 0
              response: '7E806410100076500'
            """.trimIndent(),
        )
        assertEquals(listOf("7E806410100076500"), f.cases[0].responseLines)
    }

    @Test
    fun `a multi-frame response can also be a folded double-quoted scalar`() {
        // OBDb spells a multi-frame response three different ways. This is the third:
        // a double-quoted scalar that folds over physical lines, with frames separated by
        // a literal \n escape and bytes spaced out. Read naively it becomes one enormous
        // "frame" that decodes to nothing at all.
        val f = parseFixtureYaml(
            "command_id: 7E4.7EC.220104|fc=1\n" +
                "test_cases:\n" +
                "- expected_values:\n" +
                "    IONIQ5_HVBAT_CMU065_VOLT: 3.92\n" +
                "  response: \"7EC 10 27 62 01 04 FF FF FF \\n7EC 21 FF C4 C4 C4 C4 C4 C4 \\n7EC 22 C4\n" +
                "    C4 C4 C4 C4 C4 C4 \\n7EC 23 C4 C4 C4 C4 C4 AA AA \"\n",
        )
        assertEquals(
            listOf(
                "7EC 10 27 62 01 04 FF FF FF",
                "7EC 21 FF C4 C4 C4 C4 C4 C4",
                "7EC 22 C4 C4 C4 C4 C4 C4 C4",   // folded across two physical lines
                "7EC 23 C4 C4 C4 C4 C4 AA AA",
            ),
            f.cases[0].responseLines,
        )
    }

    @Test
    fun `parses a real vendored fixture off disk`() {
        val f = parseFixtureYaml(
            fixtureText("fixtures/Kia-EV6/tests/test_cases/2022/commands/7E4.7EC.220101_fc=1.yaml"),
        )
        assertEquals("7E4.7EC.220101|fc=1", f.commandId)
        assertEquals("55.5", f.cases[0].expected["EV6_HVBAT_SOC"])
        assertEquals("7EC103E620101EFFBE7", f.cases[0].responseLines[0])
        assertEquals(9, f.cases[0].responseLines.size)
    }

    @Test
    fun `the vendored corpus is all there`() {
        // Guards against a half-finished checkout quietly turning the gate test into a
        // test of nothing.
        val expected = mapOf(
            "Kia-EV6" to 39,
            "Hyundai-Ioniq-5" to 61,
            "Hyundai-Elantra" to 574,
            "Ford-F-150" to 2141,
            "Hyundai-Sonata" to 383,
            "Hyundai-Kona" to 190,
            "Kia-Sorento" to 301,
            "Kia-Niro" to 210,
            "Kia-EV3" to 101,
            "Toyota-Prius" to 319,
            "Ram-1500" to 529,
            "Nissan-Leaf" to 187,
            "Audi-A6" to 312,
            "BMW-3-Series" to 326,
        )
        val counts = expected.keys.associateWith { fixtureFiles("fixtures/$it/tests/test_cases").size }
        assertEquals(expected, counts)
        assertEquals(5673, counts.values.sum())
    }
}
