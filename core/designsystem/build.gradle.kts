@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)   // compose.uiTest

plugins {
    id("carscan.kmp.compose")
}

// Every string in the app lives here, in one composeResources tree, so a translator sees one
// file per locale rather than seven. That only works if the feature modules can actually reach
// the generated accessors — and they are internal unless asked otherwise.
compose.resources {
    publicResClass = true
    packageOfResClass = "com.bruni.carscan.core.designsystem.generated.resources"
    generateResClass = always
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
        androidMain.dependencies {
            // BannerAd's real AdView. androidMain only — must never leak onto the iOS klib path,
            // which is why it is declared here rather than in commonMain.
            implementation(libs.play.services.ads)
        }
        commonTest.dependencies {
            implementation(compose.uiTest)
        }

        // Compose UI tests cannot live in commonTest, because androidHostTest compiles and runs
        // commonTest too — and there they die on a null android.os.Build.FINGERPRINT, since a
        // plain JVM unit test has no device, no Activity and no Looper.
        //
        // So they go in a source set shared by exactly the targets that CAN host a Compose test:
        // the JVM (headless Skiko) and iOS. Android is excluded by construction rather than by
        // remembering not to put things there.
        val skikoTest by creating { dependsOn(commonTest.get()) }

        getByName("jvmTest") {
            dependsOn(skikoTest)
            dependencies {
                implementation(compose.desktop.currentOs)   // Skiko natives for the headless renderer
            }
        }

        // Apple targets only exist on a macOS host.
        listOf("iosX64Test", "iosArm64Test", "iosSimulatorArm64Test").forEach { name ->
            findByName(name)?.dependsOn(skikoTest)
        }
    }
}
