plugins {
    id("carscan.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))

            // DataStoreEntitlementCache persists the offline entitlement cache through the same
            // KMP DataStore<Preferences> singleton :core:data's SettingsRepository reads.
            implementation(libs.androidx.datastore.preferences.core)
        }
    }
}
