import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("carscan.kmp.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// KotlinDependencyHandler has no platform(), so build the BOM constraint here.
val koinBom = dependencies.platform(libs.findLibrary("koin-bom").get())

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:data"))
            implementation(project(":core:designsystem"))

            implementation(koinBom)
            implementation(libs.findLibrary("koin-core").get())
            implementation(libs.findLibrary("koin-compose").get())
            implementation(libs.findLibrary("koin-compose-viewmodel").get())

            implementation(libs.findLibrary("jb-lifecycle-viewmodel").get())
            implementation(libs.findLibrary("jb-lifecycle-viewmodel-compose").get())
            implementation(libs.findLibrary("jb-lifecycle-runtime-compose").get())
        }
    }
}
