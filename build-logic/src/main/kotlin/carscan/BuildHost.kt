package carscan

import org.gradle.api.Project

/**
 * Kotlin/Native Apple targets require Xcode and can only be compiled on macOS.
 * On any other host we do not register them at all, so the whole Gradle build
 * still configures and runs (Android + tests) on Windows/Linux.
 *
 * Override with `-Pcarscan.enableApple=true|false` (or in gradle.properties).
 */
fun Project.appleTargetsEnabled(): Boolean {
    val override = providers.gradleProperty("carscan.enableApple").orNull
    return override?.toBoolean()
        ?: System.getProperty("os.name").lowercase().startsWith("mac")
}

/** `:core:obd` -> `com.bruni.carscan.core.obd` */
fun Project.carscanNamespace(): String =
    "com.bruni.carscan." + path.removePrefix(":").replace(':', '.').replace('-', '.')
