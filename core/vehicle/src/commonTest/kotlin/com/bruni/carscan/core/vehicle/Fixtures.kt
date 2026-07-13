package com.bruni.carscan.core.vehicle

import com.bruni.carscan.core.model.obdb.ObdbSignal
import com.bruni.carscan.core.model.obdb.Signalset

/**
 * Reads a vendored test fixture, [path] being relative to `src/commonTest/resources`.
 *
 * commonTest has no multiplatform resource API, so each target supplies its own actual.
 */
expect fun fixtureText(path: String): String

/** Names of the files under a fixture repo's `signalsets/v3` directory. */
expect fun fixtureSignalsetFileNames(repo: String): List<String>

fun obdbJson(repo: String, file: String = "default.json"): String =
    fixtureText("obdb/$repo/signalsets/v3/$file")

fun obdbSignalset(repo: String, file: String = "default.json") =
    SignalsetParser.parse(obdbJson(repo, file))

/** Every signal in the document, ignoring which command carries it. */
fun Signalset.allSignals(): List<ObdbSignal> = commands.flatMap { it.signals }

fun Signalset.signalsById(id: String): List<ObdbSignal> = allSignals().filter { it.id == id }

const val SAE_J1979 = "SAEJ1979"
const val KIA_EV6 = "Kia-EV6"
const val FORD_F150 = "Ford-F-150"
const val TEST_VARIANT = "Test-Variant"
