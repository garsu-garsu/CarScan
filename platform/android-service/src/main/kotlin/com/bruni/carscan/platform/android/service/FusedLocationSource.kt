package com.bruni.carscan.platform.android.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.bruni.carscan.core.data.GpsFix
import com.bruni.carscan.core.data.LocationSource
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The device's own GPS, as a [LocationSource].
 *
 * Fused rather than raw GPS because a phone in a moving car has Wi-Fi and cell fixes to fall back
 * on when the satellites drop under an overpass, and the fused provider blends them — the raw
 * `LocationManager` would just go quiet.
 *
 * Requesting updates without location permission throws `SecurityException`, so the flow checks
 * the grant itself and simply emits nothing when it is absent: the port's contract is "fixes while
 * collected", and a permission the user declined is a reason to have no fixes, not to crash the
 * coroutine collecting them.
 */
class FusedLocationSource(context: Context) : LocationSource {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    override val fixes: Flow<GpsFix> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) {
                    trySend(
                        GpsFix(
                            tsMs = loc.time,
                            lat = loc.latitude,
                            lon = loc.longitude,
                            altM = if (loc.hasAltitude()) loc.altitude.toFloat() else Float.NaN,
                            // Android reports m/s; the app stores km/h everywhere.
                            speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else Float.NaN,
                            bearingDeg = if (loc.hasBearing()) loc.bearing else Float.NaN,
                        ),
                    )
                }
            }
        }

        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            // Revoked between the check above and here — same outcome, no fixes.
            close()
            return@callbackFlow
        }

        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        /** 1 Hz — matches the 1-second resolution the trip series is stored at. */
        const val UPDATE_INTERVAL_MS = 1_000L
    }
}
