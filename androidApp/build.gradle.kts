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

// Play upload signing. `local.properties` supplies the real upload keystore
// (`release.storeFile`/`release.storePassword`/`release.keyAlias`/`release.keyPassword`) when one
// exists. Without it, release falls back to the Android debug keystore so a minified release build
// is still installable on a device for testing — that fallback is NOT valid for a Play upload,
// which must be signed with the real upload key.
val releaseStoreFile = localProperties.getProperty("release.storeFile")
    ?.let { rootProject.file(it) }
    ?: file(System.getProperty("user.home")).resolve(".android/debug.keystore")
val releaseStorePassword = localProperties.getProperty("release.storePassword") ?: "android"
val releaseKeyAlias = localProperties.getProperty("release.keyAlias") ?: "androiddebugkey"
val releaseKeyPassword = localProperties.getProperty("release.keyPassword") ?: "android"

android {
    namespace = "com.bruni.carscan"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bruni.carscan"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Play refuses an upload whose versionCode it has already seen, so every internal-test
        // build needs a fresh one. CI passes the run number (`-PversionCode=…`); a local build
        // gets 1 and never has to think about it.
        versionCode = (providers.gradleProperty("versionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("versionName").orNull ?: "1.0.0"

        // Test app id by default → debug never touches the real ad account.
        manifestPlaceholders["admobAppId"] = TEST_ADMOB_APP_ID
        // Same key in every build — see mapsApiKey above.
        manifestPlaceholders["mapsApiKey"] = mapsApiKey
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("release")
            // Compose Multiplatform resources live in assets/, not res/, so the resource shrinker
            // cannot see them being used and would strip every string in the app. Code shrinking
            // only.
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // TripRepository.recoverStranded(), called once from CarScanApplication.onCreate — same
    // reason :core:monetization is here: onCreate touches the port directly.
    implementation(project(":core:data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    // Play In-App Updates. Lives here rather than behind a port in :core:*, because the only
    // thing that triggers it is the Activity coming back to the foreground, and MainActivity is
    // the only thing that needs to know it exists. Android-only by construction: :androidApp is
    // not on the iOS path at all.
    implementation(libs.play.app.update.ktx)

    // ON_START/ON_STOP for the whole app's process, not one Activity — what AppOpenAdManager
    // needs to tell a real foreground return apart from a screen rotation.
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
