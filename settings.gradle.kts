pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CarScan"

// Entry points
include(":androidApp")
include(":composeApp")

// Core
include(":core:common")
include(":core:model")
include(":core:units")
include(":core:transport")
include(":core:obd")
include(":core:vehicle")
include(":core:database")
include(":core:data")
include(":core:designsystem")
include(":core:monetization")

// Features — these never depend on each other.
include(":feature:connect")
include(":feature:dashboard")
include(":feature:live")
include(":feature:hud")
include(":feature:trip")
include(":feature:settings")
include(":feature:garage")

// Android-only platform modules (the only ones allowed to use com.android.library)
include(":platform:android-ads")
include(":platform:android-service")
