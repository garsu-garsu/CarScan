package com.bruni.carscan.feature.trip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bruni.carscan.core.data.GpsPoint
import com.bruni.carscan.core.data.TripEvent

/**
 * **Never compiled.** Apple targets are only registered on a macOS host — see the note on
 * `:core:units`'s iOS `NumberFormatter` actual.
 *
 * A real map needs either MapKit interop or a Google Maps iOS SDK bridge, neither of which this
 * pass builds — this stub exists only so the source-set layout is valid.
 */
@Composable
actual fun TripMap(route: List<GpsPoint>, events: List<TripEvent>, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Map (Android only)")
    }
}
