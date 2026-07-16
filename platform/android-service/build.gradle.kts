plugins {
    id("carscan.android.lib")
}

dependencies {
    implementation(project(":core:data"))

    // Fused location for the trip GPS track, and androidx.core for the foreground-service
    // notification. Android-only, so it lives in this platform module rather than commonMain —
    // the iOS klib never sees it.
    implementation(libs.play.services.location)
    implementation(libs.androidx.core.ktx)
}
