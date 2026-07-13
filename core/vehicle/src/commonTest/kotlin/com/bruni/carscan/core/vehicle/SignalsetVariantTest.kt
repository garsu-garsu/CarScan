package com.bruni.carscan.core.vehicle

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Model-year variant selection, ported from OBDb's `python/signalsets/loader.py`.
 *
 * The rule that actually matters is the one that is easiest to get wrong: exactly ONE file
 * wins and there is NO merging with `default.json`. A merging implementation passes any test
 * that only checks "the narrow file's commands are present", so every test here also asserts
 * what must be ABSENT.
 */
class SignalsetVariantTest {

    private val files = listOf("default.json", "2020-2024.json")

    @Test
    fun `parses a year-range file name`() {
        val variant = SignalsetVariant.parse("2020-2024.json")
        variant shouldBe SignalsetVariant("2020-2024.json", from = 2020, to = 2024)
        variant!!.span shouldBe 4
    }

    @Test
    fun `default json is the widest possible range`() {
        SignalsetVariant.parse("default.json") shouldBe
            SignalsetVariant("default.json", from = 0, to = 9999)
    }

    @Test
    fun `file names that are not variants are not variants`() {
        SignalsetVariant.parse("README.md") shouldBe null
        SignalsetVariant.parse("2020.json") shouldBe null
        SignalsetVariant.parse("202-2024.json") shouldBe null
        SignalsetVariant.parse("2020-2024-2028.json") shouldBe null
        SignalsetVariant.parse("v2/default.json") shouldBe null
    }

    @Test
    fun `a year inside the narrow range picks the narrow file`() {
        SignalsetVariant.select(files, modelYear = 2022)?.fileName shouldBe "2020-2024.json"
    }

    @Test
    fun `a year outside the narrow range falls back to default`() {
        SignalsetVariant.select(files, modelYear = 2019)?.fileName shouldBe "default.json"
        SignalsetVariant.select(files, modelYear = 2025)?.fileName shouldBe "default.json"
    }

    @Test
    fun `range bounds are inclusive`() {
        SignalsetVariant.select(files, modelYear = 2020)?.fileName shouldBe "2020-2024.json"
        SignalsetVariant.select(files, modelYear = 2024)?.fileName shouldBe "2020-2024.json"
    }

    @Test
    fun `the most specific overlapping range wins`() {
        val overlapping = listOf("default.json", "2010-2030.json", "2020-2024.json")
        SignalsetVariant.select(overlapping, modelYear = 2022)?.fileName shouldBe "2020-2024.json"
        SignalsetVariant.select(overlapping, modelYear = 2015)?.fileName shouldBe "2010-2030.json"
        SignalsetVariant.select(overlapping, modelYear = 2005)?.fileName shouldBe "default.json"
    }

    @Test
    fun `selection returns nothing when there is nothing to select`() {
        SignalsetVariant.select(listOf("README.md"), modelYear = 2022) shouldBe null
        SignalsetVariant.select(emptyList(), modelYear = 2022) shouldBe null
    }

    @Test
    fun `the winning variant replaces default entirely - the two are NOT merged`() {
        val names = fixtureSignalsetFileNames(TEST_VARIANT)
        names shouldContainExactlyInAnyOrder listOf("default.json", "2020-2024.json")

        val narrow = SignalsetVariant.select(names, modelYear = 2022)!!
        narrow.fileName shouldBe "2020-2024.json"

        val set = obdbSignalset(TEST_VARIANT, narrow.fileName)
        val ids = set.allSignals().map { it.id }

        ids shouldContainExactlyInAnyOrder listOf("TV_SHARED", "TV_ONLY_IN_2020_2024")
        // A merging implementation would drag this in from default.json.
        ids shouldNotContain "TV_ONLY_IN_DEFAULT"
        // ...and a merging implementation might also keep default's scaling for the shared signal.
        set.signalsById("TV_SHARED").single().fmt.div shouldBe 2.0
    }

    @Test
    fun `outside the narrow range the default file is used whole`() {
        val names = fixtureSignalsetFileNames(TEST_VARIANT)

        val fallback = SignalsetVariant.select(names, modelYear = 2019)!!
        fallback.fileName shouldBe "default.json"

        val set = obdbSignalset(TEST_VARIANT, fallback.fileName)
        val ids = set.allSignals().map { it.id }

        ids shouldContainExactlyInAnyOrder listOf("TV_ONLY_IN_DEFAULT", "TV_SHARED")
        ids shouldNotContain "TV_ONLY_IN_2020_2024"
        set.signalsById("TV_SHARED").single().fmt.div shouldBe 1.0
    }
}
