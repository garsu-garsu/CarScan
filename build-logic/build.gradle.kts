plugins {
    `kotlin-dsl`
}

group = "com.bruni.carscan.buildlogic"

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

// The convention plugins are precompiled script plugins, so every plugin they
// apply by id must be on this build's runtime classpath.
dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.android.kmp.library.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.composeCompiler.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
}
