package com.bruni.carscan.obd

import com.bruni.carscan.core.vehicle.SignalsetParser
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.io.File
import kotlin.test.Test

/**
 * Every bundled OBDb asset — the standard set and all four curated vehicles — parses via
 * [SignalsetParser]. A file that fails this is a malformed or truncated download that would
 * otherwise only be discovered when a user picks that vehicle. See
 * `composeResources/files/obdb/SOURCE.md` for the provenance of each file.
 */
class BundledAssetsParseTest {

    @Test
    fun `every bundled obdb asset parses`() {
        // catalog.json lives in the same directory but is not a signalset — it is the
        // make/model/obdbRepo/year-range list BundledVehicleCatalog parses, checked separately.
        val jsonFiles = obdbAssetsDir().listFiles { f -> f.extension == "json" && f.name != "catalog.json" }
            .orEmpty()

        jsonFiles.map { it.name }.shouldContainExactlyInAnyOrder(
            "SAEJ1979.json",
            "Kia-EV6.json",
            "Hyundai-IONIQ-5.json",
            "Hyundai-Elantra.json",
            "Ford-F-150.json",
        )

        for (file in jsonFiles) {
            SignalsetParser.parse(file.readText()).commands.shouldNotBeEmpty()
        }
    }
}

/**
 * Gradle runs host tests with the working directory set to the module directory, so the assets
 * are simply on disk. We walk up looking for them anyway, because a test run launched from the
 * repo root or from an IDE gets a different working directory.
 */
private fun obdbAssetsDir(): File {
    val relative = "src/commonMain/composeResources/files/obdb"
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        File(dir, relative).let { if (it.isDirectory) return it }
        File(dir, "composeApp/$relative").let { if (it.isDirectory) return it }
        dir = dir.parentFile
    }
    error("Could not find composeApp/$relative from ${File(".").absolutePath}")
}
