plugins {
    id("carscan.kmp")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // api, not implementation: the OBDb DTOs are @Serializable and the modules
            // that load them (:core:vehicle, :core:database) construct Json themselves.
            api(libs.kotlinx.serialization.json)
        }
    }
}
