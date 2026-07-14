package com.bruni.carscan.core.designsystem.i18n

import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.asIsLabelKey
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import org.w3c.dom.Element

/**
 * **A missing key does not fail — it falls back to English, silently.**
 *
 * That is the whole failure mode of a half-translated app: nothing breaks, no test goes red,
 * and a Russian user just sees one English word in the middle of a sentence. There is no
 * runtime signal to assert on, so the only place to catch it is here, against the XML itself.
 *
 * Runs on the JVM target rather than in `commonTest`, because it reads the resource files off
 * disk and `commonMain` has no filesystem. The resources it checks are the shipping ones.
 */
class StringResourceParityTest {

    /** en is the source of truth. Everything else is measured against it. */
    private val source = "values"

    private val translations = listOf(
        "values-ru",
        "values-de",
        "values-pl",
        "values-pt-rBR",
        "values-es",
        "values-uk",
        "values-ko",
    )

    /**
     * The CLDR plural categories each language actually uses. Russian, Polish and Ukrainian
     * have `few` and `many`; Korean has no plural at all. Shipping `one`/`other` into Russian
     * is not "good enough" — it renders "5 запроса" where the language wants "5 запросов",
     * which reads as broken rather than as foreign.
     */
    private val pluralCategories = mapOf(
        "values" to setOf("one", "other"),
        "values-ru" to setOf("one", "few", "many", "other"),
        "values-de" to setOf("one", "other"),
        "values-pl" to setOf("one", "few", "many", "other"),
        "values-pt-rBR" to setOf("one", "other"),
        "values-es" to setOf("one", "other"),
        "values-uk" to setOf("one", "few", "many", "other"),
        "values-ko" to setOf("other"),
    )

    @Test
    fun everyLocaleWeShipHasAStringsFile() {
        for (dir in listOf(source) + translations) {
            assertTrue(stringsFile(dir).isFile, "${stringsFile(dir).absolutePath} does not exist")
        }
    }

    @Test
    fun everyEnglishKeyExistsInEveryOtherLocale() {
        val english = keys(source, "string")
        assertTrue(english.isNotEmpty(), "the English source file declares no strings at all")

        for (dir in translations) {
            val translated = keys(dir, "string")
            val missing = english - translated
            assertTrue(
                missing.isEmpty(),
                "$dir silently falls back to English for: ${missing.sorted()}",
            )
        }
    }

    @Test
    fun noLocaleDeclaresAKeyThatEnglishDoesNot() {
        // The mirror image: a key only a translator knows about is a string nothing reads, and
        // it usually means a key was renamed in en and only half the locales followed.
        val english = keys(source, "string") + keys(source, "plurals")
        for (dir in translations) {
            val extra = (keys(dir, "string") + keys(dir, "plurals")) - english
            assertTrue(extra.isEmpty(), "$dir declares keys that do not exist in English: $extra")
        }
    }

    @Test
    fun everyPluralExistsInEveryLocaleWithTheCategoriesThatLanguageActuallyUses() {
        val english = keys(source, "plurals")
        assertTrue(english.isNotEmpty(), "no plurals at all — a count is being formatted with %d")

        for (dir in listOf(source) + translations) {
            val plurals = plurals(dir)
            assertEquals(english, plurals.keys, "$dir does not declare the same plurals as English")

            val required = pluralCategories.getValue(dir)
            for ((name, quantities) in plurals) {
                assertEquals(
                    required,
                    quantities,
                    "$dir plural '$name' has the wrong CLDR categories",
                )
            }
        }
    }

    @Test
    fun noTranslationIsEmpty() {
        // An empty <string> is worse than a missing one: it does not fall back, it renders
        // nothing, and a button with no label is a button nobody presses.
        for (dir in listOf(source) + translations) {
            for ((name, value) in values(dir, "string")) {
                assertTrue(value.isNotBlank(), "$dir/$name is blank")
            }
        }
    }

    @Test
    fun everyStringKeepsTheFormatArgumentsEnglishDeclares() {
        // A translator who drops the %2$d out of "%1$s at %2$d Hz" does not produce a typo —
        // they produce a MissingFormatArgumentException, in one locale, at runtime. Positional
        // args (%1$s, not %s) because word order is not English word order: German puts the
        // count where English puts the noun.
        val english = values(source, "string").mapValues { (_, v) -> formatArgs(v) }

        for (dir in translations) {
            for ((name, value) in values(dir, "string")) {
                val expected = english[name] ?: continue
                assertEquals(expected, formatArgs(value), "$dir/$name has the wrong format args")
            }
        }
    }

    /** e.g. "%1$s at %2$d Hz" → {%1$s, %2$d}. A bare "%" (the percent unit) is not an arg. */
    private fun formatArgs(value: String): Set<String> =
        Regex("""%(\d+\$)?[a-zA-Z]""").findAll(value).map { it.value }.toSet()

    @Test
    fun everyPluralFormKeepsItsCountPlaceholder() {
        // Dropping the %d out of one quantity of one locale is a runtime crash on that one
        // branch — the one that only fires at, say, exactly 5 in Polish.
        for (dir in listOf(source) + translations) {
            val doc = parse(dir)
            for (plural in doc.elements("plurals")) {
                val name = plural.getAttribute("name")
                for (item in plural.childElements("item")) {
                    val quantity = item.getAttribute("quantity")
                    assertTrue(
                        item.textContent.contains("%d"),
                        "$dir plural '$name' quantity '$quantity' lost its %d",
                    )
                }
            }
        }
    }

    @Test
    fun everyUnitLabelKeyThatCoreUnitsCanEmitIsAStringWeActuallyShip() {
        // :core:units addresses a label by NAME — `UnitId.labelKey` is the string "unit_mph",
        // not a symbol — and the UI resolves it through `Res.allStringResources[key]` at
        // runtime. So a labelKey naming a key that does not exist is not a compile error in
        // any module: it is a crash, or a blank suffix, on the one gauge that uses that unit.
        //
        // Nothing else in the build can see both sides of that contract. :core:units has no
        // resources, and the features have no UnitId table. This module has both, so the
        // check lands here.
        val shipped = keys(source, "string")

        val missing = UnitId.entries
            .map { it.labelKey }
            .filter { it !in shipped }
        assertTrue(missing.isEmpty(), "UnitId.labelKey names strings we do not ship: $missing")

        val missingNative = ObdUnit.entries
            .mapNotNull { it.asIsLabelKey }
            .filter { it !in shipped }
        assertTrue(
            missingNative.isEmpty(),
            "ObdUnit.asIsLabelKey names strings we do not ship: $missingNative",
        )
    }

    // --- the XML, read exactly as Compose Resources reads it -------------------

    private fun stringsFile(dir: String) = File(resourcesRoot(), "$dir/strings.xml")

    private fun resourcesRoot(): File {
        // Gradle runs a test with the module directory as its working directory, but do not
        // rely on it silently: a wrong root would make every assertion here vacuous.
        val candidates = listOf(
            File("src/commonMain/composeResources"),
            File("core/designsystem/src/commonMain/composeResources"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: fail(
                "composeResources not found from ${File(".").absolutePath} — " +
                    "this test would otherwise pass by testing nothing",
            )
    }

    private fun parse(dir: String): Element =
        DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(stringsFile(dir))
            .documentElement

    private fun keys(dir: String, tag: String): Set<String> =
        parse(dir).elements(tag).map { it.getAttribute("name") }.toSet()

    private fun values(dir: String, tag: String): Map<String, String> =
        parse(dir).elements(tag).associate { it.getAttribute("name") to it.textContent }

    /** name → the set of `quantity` categories it declares. */
    private fun plurals(dir: String): Map<String, Set<String>> =
        parse(dir).elements("plurals").associate { plural ->
            plural.getAttribute("name") to
                plural.childElements("item").map { it.getAttribute("quantity") }.toSet()
        }

    private fun Element.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.childElements(tag: String): List<Element> =
        elements(tag).filter { it.parentNode === this }
}
