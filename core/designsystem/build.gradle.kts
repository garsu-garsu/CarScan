@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)   // compose.uiTest

plugins {
    id("carscan.kmp.compose")
}

kotlin {
    // Headless Skiko, so runComposeUiTest works on any host. Without it there is no way to
    // execute a Compose test on Windows at all: androidHostTest is a plain JVM Android unit
    // test with no Activity or Looper. Robolectric was the alternative and it would have cost
    // us the ability to run these same tests on iOS.
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:units"))
            // History and trip charts only. The live strip is a hand-written
            // Canvas ring-buffer renderer: Vico does not hold up at 20 Hz.
            implementation(libs.vico.multiplatform.m3)
        }
        commonTest.dependencies {
            implementation(compose.uiTest)
        }
        getByName("jvmTest").dependencies {
            implementation(compose.desktop.currentOs)   // Skiko natives for the headless renderer
        }
    }
}
