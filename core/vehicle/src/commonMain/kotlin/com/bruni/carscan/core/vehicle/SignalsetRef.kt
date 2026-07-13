package com.bruni.carscan.core.vehicle

import kotlinx.serialization.Serializable

/**
 * OBDb signalsets stay opaque data (CC BY-SA 4.0): they are parsed at runtime
 * and never code-generated into .kt, which would infect the app with BY-SA.
 */
@Serializable
data class SignalsetRef(
    val vehicleId: String,
    val path: String,
)
