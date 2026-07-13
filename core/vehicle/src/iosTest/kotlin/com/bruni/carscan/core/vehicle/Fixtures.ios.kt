package com.bruni.carscan.core.vehicle

/**
 * Apple targets are disabled on Windows, so this actual is never compiled here — it exists
 * so that a future macOS build fails on an honest TODO rather than on a missing actual.
 *
 * Implementing it means copying `src/commonTest/resources` into the test bundle (a Gradle
 * `Copy` task into the konan test executable's working directory is the usual route) and
 * reading via NSFileManager/NSString. Whoever turns on Apple targets owns this.
 */
actual fun fixtureText(path: String): String =
    TODO("iOS test fixtures not wired up: $path")

actual fun fixtureSignalsetFileNames(repo: String): List<String> =
    TODO("iOS test fixtures not wired up: $repo")
