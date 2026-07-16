plugins {
    id("carscan.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The paywall screen: BillingPort to launch a purchase, Entitlements.isPremium to
            // show the already-premium state. Pure KMP — safe on the iOS klib path too.
            implementation(project(":core:monetization"))
        }
    }
}
