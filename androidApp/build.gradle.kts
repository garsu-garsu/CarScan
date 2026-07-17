import java.util.Properties

// AGP 9 compiles Kotlin itself: applying org.jetbrains.kotlin.android here is
// an error. The KGP version is pinned by the root build's plugins block.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Google's public TEST AdMob app id. Debug builds compile it in so development never touches the
// real ad account; only release uses the real id from a gitignored `local.properties`.
val TEST_ADMOB_APP_ID = "ca-app-pub-3940256099942544~3347511713"
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { stream -> load(stream) }
}
val admobAppId: String = localProperties.getProperty("admob.app.id") ?: TEST_ADMOB_APP_ID

// The Google Maps SDK key for the trip-detail map. Unlike AdMob there is no public test key —
// the same real key is used in every build — so it comes only from the gitignored local.properties
// (`maps.api.key=...`). Absent, the map renders blank tiles rather than crashing; the route
// polyline and event markers still draw.
val mapsApiKey: String = localProperties.getProperty("maps.api.key") ?: ""

android {
    namespace = "com.bruni.carscan"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bruni.carscan"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // Test app id by default → debug never touches the real ad account.
        manifestPlaceholders["admobAppId"] = TEST_ADMOB_APP_ID
        // Same key in every build — see mapsApiKey above.
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The real app id ships only in the store (release) build.
            manifestPlaceholders["admobAppId"] = admobAppId
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":platform:android-ads"))
    implementation(project(":platform:android-service"))
    // InterstitialAdPort, AppOpenAdPort, FullScreenAdGate, Entitlements — AppOpenAdManager and
    // CarScanApplication resolve these from Koin directly. Pure KMP; no AdMob type here.
    implementation(project(":core:monetization"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // ON_START/ON_STOP for the whole app's process, not one Activity — what AppOpenAdManager
    // needs to tell a real foreground return apart from a screen rotation.
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
