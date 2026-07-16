package com.bruni.carscan.feature.garage

import com.bruni.carscan.core.data.CatalogEntry
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * TDDs the pure layout math behind the A-Z fast-scroll rail: how the grouped catalog flattens
 * into the LazyColumn's exact item order ([garageRows]), and how leading letters map onto that
 * order's header indices ([letterIndex]) and onto a drag position over the rail ([letterAt]).
 */
class GarageIndexTest {

    private fun entry(make: String, model: String) =
        CatalogEntry(make = make, model = model, obdbRepo = "$make-$model", minYear = 2020, maxYear = 2024)

    private val abarth1 = entry("Abarth", "500")
    private val abarth2 = entry("Abarth", "595")
    private val audi1 = entry("Audi", "A3")
    private val audi2 = entry("Audi", "A4")
    private val audi3 = entry("Audi", "Q5")
    private val bmw1 = entry("BMW", "3 Series")

    private val byMake = linkedMapOf(
        "Abarth" to listOf(abarth1, abarth2),
        "Audi" to listOf(audi1, audi2, audi3),
        "BMW" to listOf(bmw1),
    )

    @Test
    fun `garageRows flattens each make into one header followed by its vehicles, in order`() {
        garageRows(byMake) shouldBe listOf(
            GarageRow.Header("Abarth"),
            GarageRow.Vehicle(abarth1),
            GarageRow.Vehicle(abarth2),
            GarageRow.Header("Audi"),
            GarageRow.Vehicle(audi1),
            GarageRow.Vehicle(audi2),
            GarageRow.Vehicle(audi3),
            GarageRow.Header("BMW"),
            GarageRow.Vehicle(bmw1),
        )
    }

    @Test
    fun `garageRows on an empty catalog is empty`() {
        garageRows(emptyMap()) shouldBe emptyList()
    }

    @Test
    fun `letterIndex maps A to Abarth's header and B to BMW's header, skipping Audi's redundant A`() {
        val rows = garageRows(byMake)

        // Abarth's header is item 0; BMW's header comes after Abarth's header + 2 vehicles
        // + Audi's header + 3 vehicles = index 7. Audi is also an 'A' make, but Abarth's
        // header already claimed that letter, so Audi contributes no entry of its own.
        letterIndex(rows) shouldBe listOf('A' to 0, 'B' to 7)
    }

    @Test
    fun `letterIndex is empty for an empty row list`() {
        letterIndex(emptyList()) shouldBe emptyList()
    }

    @Test
    fun `letterIndex ignores vehicle rows and only reads header rows`() {
        val rows = listOf(
            GarageRow.Header("Kia"),
            GarageRow.Vehicle(entry("Zzz", "should not count")),
            GarageRow.Vehicle(entry("Kia", "EV6")),
        )

        letterIndex(rows) shouldBe listOf('K' to 0)
    }

    @Test
    fun `letterAt divides the rail evenly among the letters in order`() {
        val letters = listOf('A', 'B', 'C', 'D')

        letterAt(letters, offsetY = 0f, railHeight = 100f) shouldBe 'A'
        letterAt(letters, offsetY = 24f, railHeight = 100f) shouldBe 'A'
        letterAt(letters, offsetY = 26f, railHeight = 100f) shouldBe 'B'
        letterAt(letters, offsetY = 74f, railHeight = 100f) shouldBe 'C'
        letterAt(letters, offsetY = 99f, railHeight = 100f) shouldBe 'D'
    }

    @Test
    fun `letterAt clamps out-of-range drag positions to the first or last letter`() {
        val letters = listOf('A', 'B')

        letterAt(letters, offsetY = -50f, railHeight = 100f) shouldBe 'A'
        letterAt(letters, offsetY = 500f, railHeight = 100f) shouldBe 'B'
    }

    @Test
    fun `letterAt is null with no letters or no rail height`() {
        letterAt(emptyList(), offsetY = 10f, railHeight = 100f) shouldBe null
        letterAt(listOf('A'), offsetY = 10f, railHeight = 0f) shouldBe null
    }
}
