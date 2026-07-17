package com.bruni.carscan.core.data

import kotlinx.coroutines.flow.Flow

/** One location reading, in the native/SI unit it arrived in: metres, km/h, degrees. */
data class GpsFix(
    val tsMs: Long,
    val lat: Double,
    val lon: Double,
    val altM: Float,
    val speedKmh: Float,
    val bearingDeg: Float,
)

/**
 * The platform's location stream. Hot while collected — starting to collect is what
 * requests updates, and location permission is entirely the implementation's concern,
 * not this port's.
 */
interface LocationSource {
    val fixes: Flow<GpsFix>
}

/** Turns a coordinate into a human-readable address. Best-effort: never throws. */
interface ReverseGeocoder {
    /** Null on failure or while offline — never an exception. */
    suspend fun address(lat: Double, lon: Double): String?
}

/** One gyro reading: yaw rate about the vehicle's vertical axis, in rad/s. */
data class GyroSample(val tsMs: Long, val yawRateRadPerSec: Float)

/**
 * The platform's gyro stream, already rotated into the vehicle frame — the Android
 * implementation does that with the rotation vector, so this port only ever sees yaw
 * rate about the car's own vertical axis, never the raw device-frame sensor.
 */
interface GyroSource {
    val yawRate: Flow<GyroSample>
}
