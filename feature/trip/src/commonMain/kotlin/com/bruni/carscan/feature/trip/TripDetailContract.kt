package com.bruni.carscan.feature.trip

import com.bruni.carscan.core.data.GpsPoint
import com.bruni.carscan.core.data.TripEvent
import com.bruni.carscan.core.units.UnitPreferences

/** One trip's detail: its header numbers, its route, and the harsh-driving events on it. */
data class TripDetailState(
    val loading: Boolean = true,
    val startedMs: Long = 0L,
    /** Null while the trip is still recording (`endedMs` is null). */
    val durationMs: Long? = null,
    val distanceM: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    /**
     * Total fuel over total distance, in L/100km — converted to the user's unit at draw time.
     * Null when the trip has no fuel to divide (GPS-only, an EV, or a car with no fuel-rate
     * signal), and the header simply leaves the figure out rather than print a plausible zero.
     */
    val consumptionL100km: Double? = null,
    val startLat: Double? = null,
    val startLon: Double? = null,
    val endLat: Double? = null,
    val endLon: Double? = null,
    val startAddress: String? = null,
    val endAddress: String? = null,
    val units: UnitPreferences = UnitPreferences.METRIC,
    val route: List<GpsPoint> = emptyList(),
    val events: List<TripEvent> = emptyList(),
)

sealed interface TripDetailIntent {
    data class Load(val tripId: String) : TripDetailIntent
}
