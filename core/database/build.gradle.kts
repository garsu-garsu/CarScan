plugins {
    id("carscan.kmp")
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("CarScanDb") {
            packageName.set("com.bruni.carscan.db")
        }
    }
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        // iosMain only exists when the Apple targets are registered (macOS host).
        findByName("iosMain")?.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
    }
}
