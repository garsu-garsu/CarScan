import carscan.carscanNamespace
import org.gradle.api.artifacts.VersionCatalogsExtension

// Plain Android library. Only :platform:* may use this — those modules need
// manifest merging, which the AGP KMP library plugin does not support.
//
// AGP 9 compiles Kotlin itself, so org.jetbrains.kotlin.android must NOT be
// applied here.
plugins {
    id("com.android.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaVersion = JavaVersion.toVersion(libs.findVersion("jvmToolchain").get().requiredVersion)

android {
    namespace = carscanNamespace()
    compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
    }

    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
}
