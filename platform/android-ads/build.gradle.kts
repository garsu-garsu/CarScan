import java.util.Properties

plugins {
    id("carscan.android.lib")
}

// Google's public TEST ad unit ids. Debug builds always compile these in, so development never
// serves or clicks a real ad (which risks an AdMob ban). Release builds use the real ids from a
// gitignored `local.properties` (falling back to test when it's absent, e.g. on CI).
val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
val TEST_APP_OPEN = "ca-app-pub-3940256099942544/9257395921"
val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { stream -> load(stream) }
}
fun realOr(key: String, test: String) = "\"${localProperties.getProperty(key) ?: test}\""

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // Test ids by default → debug (and any variant without an override) never touches real ads.
        buildConfigField("String", "INTERSTITIAL_AD_UNIT_ID", "\"$TEST_INTERSTITIAL\"")
        buildConfigField("String", "APP_OPEN_AD_UNIT_ID", "\"$TEST_APP_OPEN\"")
        buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$TEST_BANNER\"")
    }

    buildTypes {
        getByName("release") {
            buildConfigField("String", "INTERSTITIAL_AD_UNIT_ID", realOr("admob.interstitial.adunit", TEST_INTERSTITIAL))
            buildConfigField("String", "APP_OPEN_AD_UNIT_ID", realOr("admob.appopen.adunit", TEST_APP_OPEN))
            buildConfigField("String", "BANNER_AD_UNIT_ID", realOr("admob.banner.adunit", TEST_BANNER))
        }
    }
}

dependencies {
    implementation(project(":core:monetization"))

    implementation(libs.billing.ktx)
    implementation(libs.play.services.ads)
    // UMP consent gathering — see AdsConsent.kt. Kept in this module rather than :androidApp
    // because it is requested around the same MobileAds.initialize() this module owns.
    implementation(libs.user.messaging.platform)

    // Not `kotlin("test")`: that shorthand needs the Kotlin Gradle plugin applied, and this
    // module deliberately applies none — AGP 9 compiles Kotlin itself for a plain
    // com.android.library (see carscan.android.lib.gradle.kts). kotlin-test-junit rather than
    // the bare multiplatform kotlin-test artifact, for the same reason: no Kotlin Gradle plugin
    // means no metadata-aware variant resolution to pick kotlin-test's JVM actuals automatically.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${libs.versions.kotlin.get()}")
}
