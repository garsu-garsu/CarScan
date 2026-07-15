plugins {
    id("carscan.kmp")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:model"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// M6 data-hygiene gate. OBDb signal definitions are CC BY-SA 4.0; code-generating them into
// Kotlin source would make the generated file an adaptation of BY-SA data and drag the app's own
// source under ShareAlike. This module *parses* that data at runtime from an opaque asset and must
// never embed it. A signal id is an uppercase, underscore-joined token — "MIL", "DTC_CNT",
// "FUELSYS1". Embedded signalset data means dozens to hundreds of such string literals; honest
// parsing code has essentially none (zero today). The threshold tolerates a handful of legitimate
// non-data constants (e.g. a repo slug like "SAEJ1979") and trips the moment a signal table is
// pasted in as code.
val verifyNoSignalDataInSource by tasks.registering {
    val mainSource = layout.projectDirectory.dir("src").asFileTree.matching {
        include("**/*Main/**/*.kt")
    }
    inputs.files(mainSource)
    val threshold = 10
    doLast {
        val idLiteral = Regex("\"[A-Z][A-Z0-9]{2,}(?:_[A-Z0-9]+)*\"")
        val offenders = mutableListOf<String>()
        var total = 0
        mainSource.files.forEach { file ->
            val hits = idLiteral.findAll(file.readText()).count()
            if (hits > 0) {
                total += hits
                offenders += "  ${file.relativeTo(projectDir)}: $hits"
            }
        }
        if (total > threshold) {
            throw GradleException(
                "Data-hygiene gate FAILED: $total OBDb signal-id-shaped string literals in " +
                    ":core:vehicle main source (threshold $threshold). OBDb signal definitions are " +
                    "CC BY-SA 4.0 and must stay an opaque runtime asset — never code-generated into " +
                    ".kt, which would place the app's own source under ShareAlike.\n" +
                    offenders.joinToString("\n"),
            )
        }
    }
}

tasks.named("check") { dependsOn(verifyNoSignalDataInSource) }
