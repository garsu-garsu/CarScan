package com.bruni.carscan.obd

import com.bruni.carscan.core.vehicle.EffectiveSignalset

/**
 * What the car on the other end can be asked for.
 *
 * A port rather than a call, because the answer is a licensed asset that has to be read off disk
 * (or, later, fetched and cached), and the connector must stay drivable by an emulator in a test
 * with no filesystem at all.
 *
 * Null means we do not know what this car is. The dashboard then has nothing to offer and says so,
 * which is the honest outcome — a signalset guessed wrong produces gauges that are confidently
 * mislabelled, which is worse than no gauges.
 */
fun interface SignalsetSource {
    suspend fun load(): EffectiveSignalset?
}
