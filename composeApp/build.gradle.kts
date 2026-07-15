import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("carscan.kmp.compose")
    // @Serializable navigation routes. The NavHost is type-safe or it is a pile of strings.
    alias(libs.plugins.kotlin.serialization)
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

            // The composition root is the ONE module allowed to see both sides of the :core:data
            // ports: the ELM327 stack that satisfies them, and the screens that consume them.
            // :core:data api-exports :core:transport, :core:vehicle, :core:database and
            // :core:units, so only :core:obd has to be named here.
            implementation(project(":core:obd"))

            implementation(project(":feature:connect"))
            implementation(project(":feature:dashboard"))
            implementation(project(":feature:live"))
            implementation(project(":feature:dtc"))
            implementation(project(":feature:hud"))
            implementation(project(":feature:trip"))
            implementation(project(":feature:settings"))
            implementation(project(":feature:garage"))

            implementation(koinBom)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            // :composeApp is the only module that owns a NavController.
            implementation(libs.jb.navigation.compose)
            implementation(libs.kotlinx.serialization.json)

            // Named here because the platform module constructs BleTransportFactory, whose
            // constructor mentions Kable's Scanner in its signature.
            implementation(libs.kable.core)

            // The composition root is what builds the DataStore that DefaultSettingsRepository reads.
            implementation(libs.androidx.datastore.preferences.core)

            // The model year a signalset is filtered against. Read from the clock, never written
            // down: a literal would go stale silently and a signalset filtered to the wrong year
            // drops commands without an error.
            implementation(libs.kotlinx.datetime)

            // HTTP for downloading OBDb signalsets on demand (the engine is per-platform, below).
            // This is a plain HTTPS GET to raw.githubusercontent.com — nothing to do with the
            // ktor-network raw TCP the Wi-Fi ELM327 transport uses.
            implementation(libs.ktor.client.core)
        }

        androidMain.dependencies {
            // androidContext() — the platform bindings need a Context for the SQLite driver, the
            // preferences file, the SPP radio and the settings deep-link.
            implementation(libs.koin.android)
            implementation(libs.ktor.client.okhttp)
        }

        // The iOS engine only where the Apple targets are registered (macOS host); on Windows the
        // iosMain source set does not exist, so this is a no-op rather than a configuration error.
        findByName("iosMain")?.dependencies {
            implementation(libs.ktor.client.darwin)
        }

        getByName("androidHostTest").dependencies {
            // commonTest runs as a plain JVM Android unit test, where android.database.sqlite is
            // a stub that throws on every call. The JDBC driver is the same SQLite engine, so the
            // recording tests exercise the real schema instead of a mock of it.
            implementation(libs.sqldelight.sqlite.driver)
        }
    }
}
