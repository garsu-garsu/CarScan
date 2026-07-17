package com.bruni.carscan.feature.trip

import android.os.Bundle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.bruni.carscan.core.data.GpsPoint
import com.bruni.carscan.core.data.HarshEventType
import com.bruni.carscan.core.data.TripEvent
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions

/**
 * A real map, via a plain `MapView` (play-services-maps) hosted in an `AndroidView` — not
 * maps-compose, which `:feature:trip` does not depend on. The lifecycle calls below are the whole
 * reason a raw `MapView` needs a wrapper: without them it never receives `onResume`/`onDestroy`
 * and leaks (or shows a blank surface) across configuration changes.
 */
@Composable
actual fun TripMap(route: List<GpsPoint>, events: List<TripEvent>, modifier: Modifier) {
    val context = LocalContext.current

    val mapView = remember(context) { MapView(context) }
    val routeColor = MaterialTheme.colorScheme.primary.toArgb()
    val labels = mapOf(
        HarshEventType.HARSH_ACCEL to HarshEventType.HARSH_ACCEL.label(),
        HarshEventType.HARSH_BRAKE to HarshEventType.HARSH_BRAKE.label(),
        HarshEventType.HARSH_START to HarshEventType.HARSH_START.label(),
        HarshEventType.HARSH_STOP to HarshEventType.HARSH_STOP.label(),
        HarshEventType.HARSH_CORNER to HarshEventType.HARSH_CORNER.label(),
    )

    DisposableEffect(mapView) {
        mapView.onCreate(Bundle())
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { view ->
            view.getMapAsync { map ->
                map.clear()
                drawRoute(map, route, routeColor)
                drawEvents(map, events, labels)
                // Posted so the view has already been laid out — LatLngBounds needs real width
                // and height to compute a zoom that actually fits.
                view.post { frameCamera(map, route) }
            }
        },
    )
}

private fun drawRoute(map: GoogleMap, route: List<GpsPoint>, colorArgb: Int) {
    if (route.size < 2) return
    map.addPolyline(PolylineOptions().addAll(route.map { LatLng(it.lat, it.lon) }).color(colorArgb))
}

private fun drawEvents(map: GoogleMap, events: List<TripEvent>, labels: Map<HarshEventType, String>) {
    for (event in events) {
        map.addMarker(
            MarkerOptions()
                .position(LatLng(event.lat, event.lon))
                .title(labels[event.type])
                .icon(BitmapDescriptorFactory.defaultMarker(hueFor(event.type))),
        )
    }
}

private fun hueFor(type: HarshEventType): Float = when (type) {
    HarshEventType.HARSH_ACCEL, HarshEventType.HARSH_START -> BitmapDescriptorFactory.HUE_GREEN
    HarshEventType.HARSH_BRAKE, HarshEventType.HARSH_STOP -> BitmapDescriptorFactory.HUE_RED
    HarshEventType.HARSH_CORNER -> BitmapDescriptorFactory.HUE_ORANGE
}

private fun frameCamera(map: GoogleMap, route: List<GpsPoint>) {
    when {
        route.isEmpty() -> Unit // nothing known — leave the map at its default view.
        route.size == 1 -> map.moveCamera(
            CameraUpdateFactory.newLatLngZoom(LatLng(route[0].lat, route[0].lon), 15f),
        )
        else -> {
            val bounds = LatLngBounds.Builder().apply {
                route.forEach { include(LatLng(it.lat, it.lon)) }
            }.build()
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 64))
        }
    }
}
