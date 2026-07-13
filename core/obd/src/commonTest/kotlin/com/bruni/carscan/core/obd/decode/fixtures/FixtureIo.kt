package com.bruni.carscan.core.obd.decode.fixtures

/**
 * Reads a vendored fixture, by path relative to `src/commonTest/resources/`.
 *
 * There is no common-stdlib way to read a test resource in KMP, so each target supplies
 * its own. Android host tests are the only ones that run on Windows; the Apple actual
 * exists so a Mac build still has one for every expect.
 */
expect fun fixtureText(path: String): String

/**
 * Lists every `.yaml` under [dir] (relative to `src/commonTest/resources/`), recursively,
 * as paths that [fixtureText] accepts. Sorted, so a failure report is stable.
 */
expect fun fixtureFiles(dir: String): List<String>
