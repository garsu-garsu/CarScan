package com.bruni.carscan.feature.trip

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bruni.carscan.core.data.GpsPoint
import com.bruni.carscan.core.data.TripEvent

/**
 * A trip's route, drawn as a polyline, with one marker per harsh-driving event.
 *
 * Android-only for now — see the androidMain actual for why (play-services-maps, not
 * maps-compose). The iOS and JVM actuals are stubs so the source-set layout is valid; nothing
 * calls this on those targets yet.
 */
@Composable
expect fun TripMap(route: List<GpsPoint>, events: List<TripEvent>, modifier: Modifier = Modifier)
