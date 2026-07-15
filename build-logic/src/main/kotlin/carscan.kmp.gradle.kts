import carscan.appleTargetsEnabled
import carscan.carscanNamespace
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // AGP 9 forbids com.android.library in KMP modules — this is the only option.
    id("com.android.kotlin.multiplatform.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val javaVersion = libs.findVersion("jvmToolchain").get().requiredVersion

kotlin {
    jvmToolchain(javaVersion.toInt())

    android {
        namespace = carscanNamespace()
        compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()
        minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()

        // Without this, Compose Multiplatform resources (composeResources/*.cvr — every string,
        // every bundled OBDb asset) are generated but NEVER copied into the APK's assets under
        // AGP 9's com.android.kotlin.multiplatform.library plugin, and the app dies at first
        // string with MissingResourceException. See JetBrains CMP-9547. Found the hard way on the
        // first real-device run: a Korean-locale tablet crashed on the connect screen loading
        // values-ko. Host tests never caught it — they read resources off the JVM classpath, not
        // the packaged APK.
        experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true

        // JVM host tests — this is what actually runs commonTest on Windows.
        withHostTestBuilder {}.configure {}

        compilerOptions {
            jvmTarget.set(JvmTarget.fromTarget(javaVersion))
        }
    }

    if (appleTargetsEnabled()) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: Flow and StateFlow show up in the public
            // signatures of nearly every module here.
            api(libs.findLibrary("kotlinx-coroutines-core").get())
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("turbine").get())
            implementation(libs.findLibrary("kotest-assertions-core").get())
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }
}
