package com.bruni.carscan.core.obd.decode.fixtures

/**
 * Apple targets are not built on the Windows host this module is developed on, so this
 * actual exists to keep the Mac build's expect/actual pairs complete rather than to run.
 *
 * Wiring it up means bundling `src/commonTest/resources` into the test bundle and reading
 * it through NSBundle — worth doing when someone first runs the suite on a Mac, and not
 * before. The decoder itself is pure common code, so the Android host run already covers
 * its behaviour on every target.
 */
actual fun fixtureText(path: String): String =
    TODO("Fixture loading on Apple targets: bundle commonTest/resources and read via NSBundle")

actual fun fixtureFiles(dir: String): List<String> =
    TODO("Fixture loading on Apple targets: bundle commonTest/resources and read via NSBundle")
