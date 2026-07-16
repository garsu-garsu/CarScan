plugins {
    id("carscan.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // InterstitialAdPort, FullScreenAdGate, Entitlements — the full-screen ad trigger
            // on disconnect. Pure KMP; no AdMob type reaches this module.
            implementation(project(":core:monetization"))
        }
    }
}
