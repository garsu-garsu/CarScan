plugins {
    id("carscan.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(libs.kable.core)
            implementation(libs.ktor.network)
        }
    }
}
