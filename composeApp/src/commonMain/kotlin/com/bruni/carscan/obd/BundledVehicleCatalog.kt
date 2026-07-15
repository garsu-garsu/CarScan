package com.bruni.carscan.obd

import com.bruni.carscan.core.data.CatalogEntry
import com.bruni.carscan.core.data.VehicleCatalog

/**
 * The vehicles a user can pick in the garage: four bundled, the rest download-only.
 *
 * A bundled [CatalogEntry.obdbRepo] names a file at `files/obdb/<obdbRepo>.json` — see
 * `composeResources/files/obdb/SOURCE.md` for the provenance of each. The rest carry no such
 * asset: [DefaultSignalsetProvider] fetches and caches their signalset from OBDb the first time
 * they are picked, per repo slug verified against `github.com/OBDb/<repo>` to exist.
 */
class BundledVehicleCatalog : VehicleCatalog {

    override fun all(): List<CatalogEntry> = ENTRIES

    private companion object {
        val ENTRIES = listOf(
            // Bundled — ship in the APK.
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

            // Download-only — no bundled asset; fetched from OBDb on first pick.
            CatalogEntry(
                make = "Toyota",
                model = "RAV4",
                obdbRepo = "Toyota-RAV4",
                minYear = 2019,
                maxYear = 2024,
            ),
            CatalogEntry(
                make = "Honda",
                model = "Civic",
                obdbRepo = "Honda-Civic",
                minYear = 2022,
                maxYear = 2024,
            ),
            CatalogEntry(
                make = "Tesla",
                model = "Model 3",
                obdbRepo = "Tesla-Model-3",
                minYear = 2018,
                maxYear = 2023,
            ),
            CatalogEntry(
                make = "Volkswagen",
                model = "Golf",
                obdbRepo = "Volkswagen-Golf",
                minYear = 2020,
                maxYear = 2024,
            ),
            CatalogEntry(
                make = "Ford",
                model = "Mustang Mach-E",
                obdbRepo = "Ford-Mustang-Mach-E",
                minYear = 2021,
                maxYear = 2024,
            ),
            CatalogEntry(make = "Kia", model = "Niro", obdbRepo = "Kia-Niro", minYear = 2022, maxYear = 2024),
            CatalogEntry(
                make = "Hyundai",
                model = "Kona",
                obdbRepo = "Hyundai-Kona",
                minYear = 2023,
                maxYear = 2024,
            ),
        )
    }
}
