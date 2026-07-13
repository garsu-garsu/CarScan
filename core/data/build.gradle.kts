plugins {
    id("carscan.kmp")
}

val koinBom = dependencies.platform(libs.koin.bom)

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:obd"))
            api(project(":core:vehicle"))
            api(project(":core:database"))
            api(project(":core:units"))

            implementation(koinBom)
            implementation(libs.koin.core)
            implementation(libs.androidx.datastore.preferences.core)
        }
    }
}
