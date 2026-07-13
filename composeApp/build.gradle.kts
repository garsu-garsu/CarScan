import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("carscan.kmp.compose")
}

val koinBom = dependencies.platform(libs.koin.bom)

kotlin {
    // No-op on non-Apple hosts, where the native targets are not registered.
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:designsystem"))
            implementation(project(":core:data"))

            implementation(project(":feature:connect"))
            implementation(project(":feature:dashboard"))
            implementation(project(":feature:live"))
            implementation(project(":feature:dtc"))
            implementation(project(":feature:hud"))
            implementation(project(":feature:trip"))
            implementation(project(":feature:settings"))

            implementation(koinBom)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            // :composeApp is the only module that owns a NavController.
            implementation(libs.jb.navigation.compose)
        }
    }
}
