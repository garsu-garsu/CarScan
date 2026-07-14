plugins {
    id("carscan.kmp")
}

kotlin {
    jvm()   // required so :core:designsystem's JVM target can resolve this module

    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
        }
    }
}
