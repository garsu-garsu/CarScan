import java.util.Properties

plugins {
    id("carscan.android.lib")
}

// Google's public TEST rewarded ad unit id, safe to compile in. Overridable per machine via a
// gitignored `local.properties` key so the real id never has to touch source control.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { stream -> load(stream) }
}
val rewardedAdUnitId: String = localProperties.getProperty("admob.rewarded.adunit")
    ?: "ca-app-pub-3940256099942544/5224354917"

android {
    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"$rewardedAdUnitId\"")
    }
}

dependencies {
    implementation(project(":core:monetization"))

    implementation(libs.billing.ktx)
    implementation(libs.play.services.ads)

    // Not `kotlin("test")`: that shorthand needs the Kotlin Gradle plugin applied, and this
    // module deliberately applies none — AGP 9 compiles Kotlin itself for a plain
    // com.android.library (see carscan.android.lib.gradle.kts). kotlin-test-junit rather than
    // the bare multiplatform kotlin-test artifact, for the same reason: no Kotlin Gradle plugin
    // means no metadata-aware variant resolution to pick kotlin-test's JVM actuals automatically.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${libs.versions.kotlin.get()}")
}
