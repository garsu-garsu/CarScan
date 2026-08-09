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
        androidMain.dependencies {
            // The Storage Access Framework file chooser for backup export/import, which is only
            // reachable through rememberLauncherForActivityResult. androidMain only — this must
            // never reach the iOS klib path, the same rule :core:designsystem follows for ads.
            implementation(libs.androidx.activity.compose)
        }
    }
}
