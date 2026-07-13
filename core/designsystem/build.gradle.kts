plugins {
    id("carscan.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:units"))
            // History and trip charts only. The live strip is a hand-written
            // Canvas ring-buffer renderer: Vico does not hold up at 20 Hz.
            implementation(libs.vico.multiplatform.m3)
        }
    }
}
