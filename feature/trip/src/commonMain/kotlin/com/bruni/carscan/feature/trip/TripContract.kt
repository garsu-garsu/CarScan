package com.bruni.carscan.feature.trip

import com.bruni.carscan.core.units.UnitPreferences

/** Which trips the list shows. `MY_CAR` is source `"OBD"`; `AUTO` is source `"GPS"`. */
enum class TripFilter { ALL, MY_CAR, AUTO }

/** One trip, ready for the screen to format. Distance/duration/speed are carried through untouched. */
data class TripRow(
    val id: String,
    val startedMs: Long,
    /** Null while the trip is still recording (`endedMs` is null). */
    val durationMs: Long?,
    val distanceM: Double,
    val maxSpeedKmh: Double,
    val source: String,
    val startAddress: String?,
    val endAddress: String?,
)

data class TripListState(
    val trips: List<TripRow> = emptyList(),
    val filter: TripFilter = TripFilter.ALL,
    val units: UnitPreferences = UnitPreferences.METRIC,
    val loading: Boolean = false,
)

sealed interface TripIntent {
    data class SetFilter(val filter: TripFilter) : TripIntent
    data object Refresh : TripIntent

    /** A trip's card was tapped. */
    data class OpenTrip(val tripId: String) : TripIntent
}

sealed interface TripListEffect {
    /** Features never depend on each other: the screen asks, and `:composeApp` navigates. */
    data class OpenTrip(val tripId: String) : TripListEffect
}
