package com.bruni.carscan.core.data

import kotlinx.serialization.Serializable

/**
 * The vehicles a user can pick from the garage — the full OBDb catalog, bundled as a JSON asset.
 *
 * A [CatalogEntry] is *display metadata*: make, model, the years it covers, and the OBDb
 * repository slug. It is deliberately NOT the OBDb signal tables — the slug is only the key the
 * composition root uses to find the bundled signalset asset and load it. Kept behind a port so the
 * picker feature never has to know where those assets live, how many ship, or how they are fetched.
 *
 * `@Serializable`: this is decoded straight off `composeResources/files/obdb/catalog.json`.
 */
@Serializable
data class CatalogEntry(
    val make: String,
    val model: String,
    /** OBDb repository slug, e.g. "Kia-EV6" — resolves to a bundled signalset asset in the app. */
    val obdbRepo: String,
    /** Earliest model year this entry covers. */
    val minYear: Int,
    /** Latest model year this entry covers. */
    val maxYear: Int,
) {
    val displayName: String get() = "$make $model"
}

/**
 * Source of the pickable vehicle list. Implemented in the composition root (`:composeApp`), which
 * is the only place that knows the bundled assets, exactly as [ObdConnector]/[ActiveVehicle] are.
 *
 * `suspend`: the real implementation reads a bundled JSON asset, which on every target is I/O.
 */
fun interface VehicleCatalog {
    suspend fun all(): List<CatalogEntry>
}
