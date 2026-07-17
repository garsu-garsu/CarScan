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
 * No-op. There is no genuine JVM target consuming this today — this file exists only so
 * `:feature:trip`'s source-set layout mirrors `:feature:hud`'s exactly (commonMain + androidMain +
 * iosMain + this jvmMain).
 */
@Composable
actual fun TripMap(route: List<GpsPoint>, events: List<TripEvent>, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Map (Android only)")
    }
}
