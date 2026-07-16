package com.bruni.carscan.feature.garage

import com.bruni.carscan.core.data.CatalogEntry

/** One row of the grouped garage list — either a manufacturer's sticky header or one of its vehicles. */
sealed interface GarageRow {
    data class Header(val make: String) : GarageRow
    data class Vehicle(val entry: CatalogEntry) : GarageRow
}

/**
 * Flattens the grouped catalog into the exact item order the LazyColumn renders: one
 * [GarageRow.Header] per make, immediately followed by that make's [GarageRow.Vehicle] rows,
 * in [byMake]'s iteration order. Keeping this list flat is what makes [letterIndex]'s header
 * indices line up exactly with the LazyColumn's item indices.
 */
fun garageRows(byMake: Map<String, List<CatalogEntry>>): List<GarageRow> =
    byMake.flatMap { (make, entries) -> listOf(GarageRow.Header(make)) + entries.map(GarageRow::Vehicle) }

/**
 * Maps each distinct leading letter found among [rows]' headers to that header's item index in
 * [rows] — the index the A-Z rail scrolls the LazyColumn to for that letter. Letters are ordered
 * by first appearance; when two makes share a leading letter, the earlier one wins and the later
 * one contributes no separate entry.
 */
fun letterIndex(rows: List<GarageRow>): List<Pair<Char, Int>> {
    val seen = mutableSetOf<Char>()
    val result = mutableListOf<Pair<Char, Int>>()
    rows.forEachIndexed { index, row ->
        if (row is GarageRow.Header) {
            val letter = row.make.firstOrNull()?.uppercaseChar()
            if (letter != null && seen.add(letter)) {
                result += letter to index
            }
        }
    }
    return result
}

/**
 * Which of [letters] a drag at [offsetY] resolves to, evenly dividing a rail of [railHeight]
 * among them in order. Out-of-bounds offsets clamp to the first/last letter. Null when there are
 * no letters to pick from or the rail has no measured height yet.
 */
fun letterAt(letters: List<Char>, offsetY: Float, railHeight: Float): Char? {
    if (letters.isEmpty() || railHeight <= 0f) return null
    val clamped = offsetY.coerceIn(0f, railHeight)
    val index = (clamped / railHeight * letters.size).toInt().coerceIn(0, letters.size - 1)
    return letters[index]
}
