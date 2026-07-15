package com.bruni.carscan.obd

import com.bruni.carscan.core.data.CatalogEntry
import com.bruni.carscan.core.data.VehicleCatalog

/**
 * The curated vehicles a user can pick in the garage, until there is somewhere to fetch a wider
 * catalog from.
 *
 * Each [CatalogEntry.obdbRepo] names a file bundled at `files/obdb/<obdbRepo>.json` —
 * [BundledSignalsetSource] is what actually resolves and loads it. See
 * `composeResources/files/obdb/SOURCE.md` for the provenance of each asset.
 */
class BundledVehicleCatalog : VehicleCatalog {

    override fun all(): List<CatalogEntry> = ENTRIES

    private companion object {
        val ENTRIES = listOf(
            CatalogEntry(make = "Kia", model = "EV6", obdbRepo = "Kia-EV6", minYear = 2022, maxYear = 2024),
            CatalogEntry(
                make = "Hyundai",
                model = "Ioniq 5",
                obdbRepo = "Hyundai-Ioniq-5",
                minYear = 2022,
                maxYear = 2024,
            ),
            CatalogEntry(
                make = "Hyundai",
                model = "Elantra",
                obdbRepo = "Hyundai-Elantra",
                minYear = 2021,
                maxYear = 2024,
            ),
            CatalogEntry(make = "Ford", model = "F-150", obdbRepo = "Ford-F-150", minYear = 2015, maxYear = 2024),
        )
    }
}
