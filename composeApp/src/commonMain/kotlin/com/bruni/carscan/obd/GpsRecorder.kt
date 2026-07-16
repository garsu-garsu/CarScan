package com.bruni.carscan.obd

import com.bruni.carscan.core.data.ActiveTrip
import com.bruni.carscan.core.data.GpsFix
import com.bruni.carscan.core.data.LocationSource
import com.bruni.carscan.core.data.ReverseGeocoder
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.database.GpsFixSample
import com.bruni.carscan.core.database.GpsWriter
import com.bruni.carscan.db.CarScanDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Puts a trip's route on disk — driven entirely by [TripRepository.activeTrip], the
 * same way [TripRecorder] is driven by the connection/settings combination.
 *
 * Every fix is written as it arrives (via [GpsWriter]); the first fix of a trip sets
 * its start location, the last one seen before the trip ends sets its end location.
 * Reverse-geocoding never blocks fix collection: it happens in a separate launch, so
 * a slow or offline lookup costs nothing but its own coroutine.
 */
class GpsRecorder(
    private val db: CarScanDb,
    private val trips: TripRepository,
    private val location: LocationSource,
    private val geocoder: ReverseGeocoder,
) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            var current: TripRecording? = null
            trips.activeTrip.collect { active ->
                current?.end()
                current = active?.let { TripRecording(it, scope).apply { begin() } }
            }
        }
    }

    private inner class TripRecording(private val trip: ActiveTrip, private val scope: CoroutineScope) {
        private val writer = GpsWriter(db, scope)
        private var firstFix: GpsFix? = null
        private var lastFix: GpsFix? = null
        private var job: Job? = null

        fun begin() {
            writer.start(trip.id, trip.startedMs)
            job = scope.launch {
                location.fixes.collect { fix ->
                    writer.offer(fix.toSample())
                    lastFix = fix
                    if (firstFix == null) {
                        firstFix = fix
                        // A separate launch: the poller-equivalent here is fix collection, and a
                        // slow or offline geocoder must never stall it.
                        launch {
                            val address = geocoder.address(fix.lat, fix.lon)
                            trips.setStartLocation(trip.id, fix.lat, fix.lon, address)
                        }
                    }
                }
            }
        }

        suspend fun end() {
            job?.cancel()
            job = null
            writer.stop()
            val fix = lastFix ?: return // no fix ever arrived: nothing to set
            val address = geocoder.address(fix.lat, fix.lon)
            trips.setEndLocation(trip.id, fix.lat, fix.lon, address)
        }
    }
}

private fun GpsFix.toSample() = GpsFixSample(
    tsMs = tsMs, lat = lat, lon = lon, altM = altM, speedKmh = speedKmh, bearingDeg = bearingDeg,
)
