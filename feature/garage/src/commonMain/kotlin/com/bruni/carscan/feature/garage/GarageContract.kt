package com.bruni.carscan.feature.garage

import com.bruni.carscan.core.data.CatalogEntry

data class GarageState(
    val entries: List<CatalogEntry> = emptyList(),
) {
    /** The picker reads as a list of makes, each with the models it covers. */
    val byMake: Map<String, List<CatalogEntry>> get() = entries.groupBy { it.make }
}

sealed interface GarageIntent {
    /** The user tapped a vehicle. Records it and makes it the active one. */
    data class Select(val entry: CatalogEntry) : GarageIntent
}

sealed interface GarageEffect {
    /** A vehicle was picked and is now active. Features never navigate themselves — see App.kt. */
    data object Selected : GarageEffect
}
