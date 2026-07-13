package com.bruni.carscan.core.vehicle

import java.io.File

/** Gradle runs host tests with the working directory set to the module directory. */
private val resources = File("src/commonTest/resources")

private fun resolve(path: String): File {
    val file = File(resources, path)
    check(file.isFile) {
        "Fixture '$path' not found at ${file.absolutePath} (working dir ${File(".").absolutePath})"
    }
    return file
}

actual fun fixtureText(path: String): String = resolve(path).readText()

actual fun fixtureSignalsetFileNames(repo: String): List<String> {
    val dir = File(resources, "obdb/$repo/signalsets/v3")
    check(dir.isDirectory) { "Fixture repo '$repo' not found at ${dir.absolutePath}" }
    return dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()
}
