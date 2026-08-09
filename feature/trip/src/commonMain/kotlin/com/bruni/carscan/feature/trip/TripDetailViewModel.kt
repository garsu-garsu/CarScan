package com.bruni.carscan.feature.trip

import com.bruni.carscan.core.common.mvi.MviViewModel
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.units.ConsumptionAggregate
import kotlinx.coroutines.launch

/**
 * One trip's detail: the header numbers, its GPS route, and the harsh-driving events on it.
 *
 * No one-shot events — this screen navigates nowhere — so the effect type is [Nothing], same
 * reasoning as [TripListViewModel] before it grew [TripListEffect].
 */
class TripDetailViewModel(
    private val trips: TripRepository,
    settings: SettingsRepository,
) : MviViewModel<TripDetailState, TripDetailIntent, Nothing>(TripDetailState()) {

    init {
        settings.settings.collectIntoState { current ->
            if (current.units != state.value.units) setState { copy(units = current.units) }
        }
    }

    override fun onIntent(intent: TripDetailIntent) {
        when (intent) {
            is TripDetailIntent.Load -> load(intent.tripId)
        }
    }

    private fun load(tripId: String) {
        scope.launch {
            val summary = trips.summary(tripId)
            val route = trips.track(tripId)
            val events = trips.events(tripId)
            setState {
                copy(
                    loading = false,
                    startedMs = summary?.startedMs ?: 0L,
                    durationMs = summary?.endedMs?.let { it - (summary.startedMs) },
                    distanceM = summary?.distanceM ?: 0.0,
                    maxSpeedKmh = summary?.maxSpeedKmh ?: 0.0,
                    // SUM(fuel) / SUM(distance), never an average of ratios — see
                    // ConsumptionAggregate. NaN (nothing driven, or no fuel reported) drops the
                    // figure from the header entirely.
                    consumptionL100km = summary
                        ?.let { ConsumptionAggregate.ofRaw(it.fuelMl, it.distanceM).asL100km() }
                        ?.takeIf { !it.isNaN() },
                    startLat = summary?.startLat,
                    startLon = summary?.startLon,
                    endLat = summary?.endLat,
                    endLon = summary?.endLon,
                    startAddress = summary?.startAddress,
                    endAddress = summary?.endAddress,
                    route = route,
                    events = events,
                )
            }
        }
    }
}
