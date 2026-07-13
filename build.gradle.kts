// Root build file. Plugins are declared here (apply false) so that subprojects
// and build-logic convention plugins can apply them by id without re-declaring
// versions. Every version lives in gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.kmp.library) apply false
    // Declaring KGP here pins one Kotlin version for the whole build, including
    // the Kotlin that AGP 9 now applies on its own inside Android modules.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
}
