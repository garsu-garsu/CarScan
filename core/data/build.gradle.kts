plugins {
    id("carscan.kmp")
}

val koinBom = dependencies.platform(libs.koin.bom)

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Deliberately NOT :core:obd. A repository needs the *shape* of the sample stream,
            // not the ELM327 implementation behind it. :core:data declares the port; :core:obd
            // satisfies it; :composeApp binds the two. That keeps the OBD stack swappable (the
            // emulator is a drop-in), keeps the half-duplex machinery out of reach of anything
            // above it, and means a broken protocol layer cannot turn the repository's tests red
            // for reasons that have nothing to do with the repository.
            api(project(":core:vehicle"))
            api(project(":core:database"))
            api(project(":core:units"))

            implementation(koinBom)
            implementation(libs.koin.core)
            implementation(libs.androidx.datastore.preferences.core)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}
