package com.bruni.carscan.feature.trip

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.data.TripSummary
import kotlinx.coroutines.launch

/**
 * The trip list: every recorded drive, newest first, filterable by how it was recorded.
 *
 * Tapping a trip asks to open its detail via [TripListEffect.OpenTrip] — this VM never navigates
 * itself, same reasoning as `DashboardViewModel`'s `OpenLiveChart`.
 */
class TripListViewModel(
    private val trips: TripRepository,
    settings: SettingsRepository,
) : MviViewModel<TripListState, TripIntent, TripListEffect>(TripListState(loading = true)) {

    /** Every trip, unfiltered — [TripListState.trips] is this, narrowed by [TripListState.filter]. */
    private var allRows: List<TripRow> = emptyList()

    init {
        load()
        settings.settings.collectIntoState { current ->
            if (current.units != state.value.units) setState { copy(units = current.units) }
        }
    }

    override fun onIntent(intent: TripIntent) {
        when (intent) {
            is TripIntent.SetFilter -> setState { copy(filter = intent.filter, trips = allRows.filteredBy(intent.filter)) }
            TripIntent.Refresh -> load()
            is TripIntent.OpenTrip -> emitEffect(TripListEffect.OpenTrip(intent.tripId))
        }
    }

    private fun load() {
        scope.launch {
            allRows = trips.allTrips().map { it.toRow() }
            setState { copy(trips = allRows.filteredBy(filter), loading = false) }
        }
    }
}

private fun List<TripRow>.filteredBy(filter: TripFilter): List<TripRow> = when (filter) {
    TripFilter.ALL -> this
    TripFilter.MY_CAR -> filter { it.source == "OBD" }
    TripFilter.AUTO -> filter { it.source == "GPS" }
}

private fun TripSummary.toRow() = TripRow(
    id = id,
    startedMs = startedMs,
    durationMs = endedMs?.let { it - startedMs },
    distanceM = distanceM,
    maxSpeedKmh = maxSpeedKmh,
    source = source,
    startAddress = startAddress,
    endAddress = endAddress,
)
