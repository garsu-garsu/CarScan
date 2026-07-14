package com.bruni.carscan.core.designsystem.i18n

import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.asIsLabelKey
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every unit label key that `:core:units` can name must actually exist in `strings.xml`.
 *
 * `:core:units` cannot depend on `composeResources`, so it hands out **plain `String` keys** —
 * `ObdUnit.asIsLabelKey` and `UnitId.labelKey`. That means the compiler checks nothing. Delete a
 * key, rename one, or typo one, and the build stays green: the failure is a lookup that returns
 * nothing, at runtime, on a gauge, in a locale nobody on the team reads.
 *
 * That is the same shape as every other bug this project has had to dig out — the code looks
 * finished, the tests pass, and the number on screen has no unit beside it, or worse, the wrong
 * one. So the guarantee the type system cannot give is asserted here instead: `:core:designsystem`
 * can see both `:core:units` and the XML, so it is the one place the two can be held against each
 * other, and a broken key becomes a failed build rather than a silent blank.
 */
class UnitLabelKeyTest {

    @Test
    fun `every ObdUnit label key exists in strings xml`() {
        val declared = keys()
        val named = ObdUnit.entries.mapNotNull { it.asIsLabelKey }.toSet()

        assertTrue(named.isNotEmpty(), "ObdUnit names no label keys at all — the mapping is empty")

        val missing = (named - declared).sorted()
        assertTrue(
            missing.isEmpty(),
            "ObdUnit.asIsLabelKey names keys that do not exist in strings.xml, so a gauge would " +
                "render a number with nothing after it: $missing",
        )
    }

    @Test
    fun `every UnitId label key exists in strings xml`() {
        val declared = keys()
        val named = UnitId.entries.map { it.labelKey }.toSet()

        val missing = (named - declared).sorted()
        assertTrue(
            missing.isEmpty(),
            "UnitId.labelKey names keys that do not exist in strings.xml: $missing",
        )
    }

    /**
     * An angle is not a temperature.
     *
     * OBDb's `degrees` is ignition timing advance and steering angle — a bare `°`. Label it
     * `unit_celsius` and a timing-advance gauge reads "14 °C": the value is right, the unit beside
     * it is a lie, and nothing about the reading looks wrong. This has already been wrong once.
     */
    @Test
    fun `degrees is an angle and never a temperature`() {
        assertTrue(
            ObdUnit.DEGREES.asIsLabelKey == "unit_degrees",
            "ObdUnit.DEGREES is labelled '${ObdUnit.DEGREES.asIsLabelKey}'. It is an ANGLE — " +
                "ignition timing, steering — not a temperature. Labelling it as celsius puts a " +
                "plausible, invisible lie on a gauge.",
        )
    }

    private fun keys(): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(stringsFile())
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it).attributes.getNamedItem("name")?.nodeValue }
            .toSet()
    }

    private fun stringsFile(): File =
        listOf(
            File("src/commonMain/composeResources/values/strings.xml"),
            File("core/designsystem/src/commonMain/composeResources/values/strings.xml"),
        ).first { it.isFile }
}
