package com.bruni.carscan.obd

import com.bruni.carscan.core.data.CatalogEntry
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private const val CATALOG_JSON = """
[
  {"make":"Kia","model":"EV6","obdbRepo":"Kia-EV6","minYear":1996,"maxYear":2025},
  {"make":"Ford","model":"F-150","obdbRepo":"Ford-F-150","minYear":2015,"maxYear":2024}
]
"""

/**
 * [parseCatalog] against the real asset's shape — an array of make/model/obdbRepo/minYear/maxYear
 * objects, see `composeResources/files/obdb/catalog.json` — and [BundledVehicleCatalog] against
 * the same injectable-asset seam [DefaultSignalsetProviderTest] uses for the signalset assets.
 */
class BundledVehicleCatalogTest {

    @Test
    fun `parseCatalog maps every field of the catalog JSON`() {
        parseCatalog(CATALOG_JSON) shouldBe listOf(
            CatalogEntry(make = "Kia", model = "EV6", obdbRepo = "Kia-EV6", minYear = 1996, maxYear = 2025),
            CatalogEntry(make = "Ford", model = "F-150", obdbRepo = "Ford-F-150", minYear = 2015, maxYear = 2024),
        )
    }

    @Test
    fun `parseCatalog ignores unknown keys, the same tolerance SignalsetParser gives OBDb JSON`() {
        val json = """[{"make":"Kia","model":"EV6","obdbRepo":"Kia-EV6","minYear":1996,"maxYear":2025,"extra":true}]"""

        parseCatalog(json) shouldBe listOf(
            CatalogEntry(make = "Kia", model = "EV6", obdbRepo = "Kia-EV6", minYear = 1996, maxYear = 2025),
        )
    }

    @Test
    fun `all reads the bundled asset and decodes it into catalog entries`() = runTest {
        val catalog = BundledVehicleCatalog(readAsset = assetsOf("files/obdb/catalog.json" to CATALOG_JSON))

        catalog.all() shouldBe listOf(
            CatalogEntry(make = "Kia", model = "EV6", obdbRepo = "Kia-EV6", minYear = 1996, maxYear = 2025),
            CatalogEntry(make = "Ford", model = "F-150", obdbRepo = "Ford-F-150", minYear = 2015, maxYear = 2024),
        )
    }

    @Test
    fun `all reads the asset only once, caching the parsed list for later calls`() = runTest {
        var reads = 0
        val catalog = BundledVehicleCatalog(readAsset = { path ->
            reads++
            path shouldBe "files/obdb/catalog.json"
            CATALOG_JSON
        })

        catalog.all()
        catalog.all()

        reads shouldBe 1
    }
}

private fun assetsOf(vararg pairs: Pair<String, String>): suspend (String) -> String {
    val assets = pairs.toMap()
    return { path -> assets[path] ?: error("No fake asset at $path") }
}
