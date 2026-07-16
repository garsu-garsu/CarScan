import java.util.Properties

// AGP 9 compiles Kotlin itself: applying org.jetbrains.kotlin.android here is
// an error. The KGP version is pinned by the root build's plugins block.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// Google's public TEST AdMob app id, safe to compile in. Overridable per machine via a
// gitignored `local.properties` key so the real id never has to touch source control.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { stream -> load(stream) }
}
val admobAppId: String = localProperties.getProperty("admob.app.id")
    ?: "ca-app-pub-3940256099942544~3347511713"

android {
    namespace = "com.bruni.carscan"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.bruni.carscan"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        manifestPlaceholders["admobAppId"] = admobAppId
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
