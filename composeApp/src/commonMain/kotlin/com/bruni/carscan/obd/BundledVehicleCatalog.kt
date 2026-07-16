package com.bruni.carscan.obd

import carscan.composeapp.generated.resources.Res
import com.bruni.carscan.core.data.CatalogEntry
import com.bruni.carscan.core.data.VehicleCatalog
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * The full OBDb vehicle catalog — every make/model/repo/year-range OBDb ships a signalset for —
 * read from the bundled `files/obdb/catalog.json` asset. See that file's provenance note at
 * `composeResources/files/obdb/SOURCE.md`.
 *
 * Only [Kia-EV6][com.bruni.carscan.core.data.CatalogEntry.obdbRepo], Hyundai-Elantra,
 * Hyundai-IONIQ-5 and Ford-F-150 also have a bundled *signalset* asset at
 * `files/obdb/<obdbRepo>.json` — every other entry is download-only: [DefaultSignalsetProvider]
 * fetches and caches its signalset from OBDb the first time it is picked.
 */
class BundledVehicleCatalog(
    /**
     * Reads the bundled catalog asset by its `composeResources`-relative path. Injected so a test
     * can supply fake JSON without compose resources on the test classpath — same seam
     * [BundledSignalsetSource]/[DefaultSignalsetProvider] use; production uses [readComposeAsset].
     */
    private val readAsset: suspend (path: String) -> String = ::readComposeAsset,
) : VehicleCatalog {

    // The asset never changes underneath a running process — read and parse it once, not on
    // every screen visit.
    private var cache: List<CatalogEntry>? = null

    override suspend fun all(): List<CatalogEntry> =
        cache ?: parseCatalog(readAsset(CATALOG_PATH)).also { cache = it }

    private companion object {
        const val CATALOG_PATH = "files/obdb/catalog.json"
    }
}

private val catalogJson = Json { ignoreUnknownKeys = true }

/**
 * Pure so it can be TDD'd with a small inline JSON fixture, with no compose resources on the test
 * classpath — [BundledVehicleCatalog.all] is just this plus [readComposeAsset].
 */
internal fun parseCatalog(json: String): List<CatalogEntry> = catalogJson.decodeFromString(json)

@OptIn(ExperimentalResourceApi::class)
private suspend fun readComposeAsset(path: String): String = Res.readBytes(path).decodeToString()
