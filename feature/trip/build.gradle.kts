plugins {
    id("carscan.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Formats a trip's startedMs into the device's local date/time — see TripScreen.
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            // The trip-detail map. androidMain only: the `TripMap` composable is expect/actual,
            // and only the Android actual draws a real map (via a MapView in an AndroidView) — the
            // iOS/JVM actuals are stubs, so play-services-maps never reaches the iOS klib.
            implementation(libs.play.services.maps)
        }
    }
}
