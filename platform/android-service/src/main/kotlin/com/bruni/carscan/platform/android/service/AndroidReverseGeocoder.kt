package com.bruni.carscan.platform.android.service

import android.content.Context
import android.location.Address
import android.location.Geocoder
import com.bruni.carscan.core.data.ReverseGeocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Turns a coordinate into a street address with the platform [Geocoder].
 *
 * The synchronous `getFromLocation` is deprecated on API 33+ in favour of a callback overload, but
 * it is still the only form that exists on every version the app supports, and it is called off the
 * main thread here anyway. Best-effort by contract: no geocoder backend, no network, or no match
 * all mean a null address, never an exception — a trip with an unknown start is still a trip.
 */
class AndroidReverseGeocoder(context: Context) : ReverseGeocoder {

    private val appContext = context.applicationContext

    override suspend fun address(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        try {
            val geocoder = Geocoder(appContext, Locale.getDefault())
            @Suppress("DEPRECATION")
            val results = geocoder.getFromLocation(lat, lon, 1)
            results?.firstOrNull()?.let(::format)
        } catch (_: Exception) {
            null
        }
    }

    /** The full formatted line if the backend gave one, else the most specific parts it did. */
    private fun format(a: Address): String? {
        a.getAddressLine(0)?.let { return it }
        val parts = listOfNotNull(a.thoroughfare, a.subLocality, a.locality, a.adminArea)
        return parts.joinToString(" ").ifBlank { null }
    }
}
