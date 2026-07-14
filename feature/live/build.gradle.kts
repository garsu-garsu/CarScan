plugins {
    id("carscan.feature")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // Historical/trip charts only. :core:designsystem keeps Vico as `implementation` on
            // purpose, so it does not land on every feature's classpath and tempt someone to
            // reach for it in the live strip — where its per-frame allocations do not survive
            // 20 Hz. The live strip is the hand-written Canvas renderer.
            implementation(libs.vico.multiplatform.m3)
        }
    }
}
