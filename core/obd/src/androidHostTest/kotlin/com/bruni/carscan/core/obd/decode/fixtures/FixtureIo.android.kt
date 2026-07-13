package com.bruni.carscan.core.obd.decode.fixtures

import java.io.File

/**
 * Gradle runs host tests with the working directory set to the module directory, so the
 * resources are simply on disk. We walk up looking for them anyway, because a test run
 * launched from the repo root or from an IDE gets a different working directory, and a
 * "fixture not found" failure that only reproduces on someone else's machine is a waste
 * of a day.
 */
private val resourcesRoot: File by lazy {
    val relative = File("core/obd/src/commonTest/resources")
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        File(dir, "src/commonTest/resources").let { if (it.isDirectory) return@lazy it }
        File(dir, relative.path).let { if (it.isDirectory) return@lazy it }
        dir = dir.parentFile
    }
    error("Could not find core/obd/src/commonTest/resources from ${File(".").absolutePath}")
}

actual fun fixtureText(path: String): String {
    val f = File(resourcesRoot, path)
    require(f.isFile) { "No fixture at $path (looked in ${f.absolutePath})" }
    return f.readText()
}

actual fun fixtureFiles(dir: String): List<String> {
    val root = File(resourcesRoot, dir)
    require(root.isDirectory) { "No fixture directory at $dir (looked in ${root.absolutePath})" }
    return root.walkTopDown()
        .filter { it.isFile && it.extension == "yaml" }
        .map { it.relativeTo(resourcesRoot).invariantSeparatorsPath }
        .sorted()
        .toList()
}
