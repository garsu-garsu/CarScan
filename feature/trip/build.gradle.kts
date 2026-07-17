plugins {
    id("carscan.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Formats a trip's startedMs into the device's local date/time — see TripScreen.
            implementation(libs.kotlinx.datetime)
        }
    }
}
