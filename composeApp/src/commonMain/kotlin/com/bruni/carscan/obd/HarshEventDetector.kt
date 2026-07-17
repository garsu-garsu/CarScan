package com.bruni.carscan.obd

import com.bruni.carscan.core.data.ActiveTrip
import com.bruni.carscan.core.data.GpsFix
import com.bruni.carscan.core.data.GyroSource
import com.bruni.carscan.core.data.HarshEventType
import com.bruni.carscan.core.data.LocationSource
import com.bruni.carscan.core.data.TripEvent
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.data.newUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Detects harsh-driving maneuvers — hard accel/brake/corner, and the accel-from-a-stop /
 * brake-to-a-stop variants — from [LocationSource.fixes] (1 Hz) and [GyroSource.yawRate], and
 * records one [TripEvent] per maneuver at its peak severity via [TripRepository.recordEvent].
 *
 * Driven entirely by [TripRepository.activeTrip], the same way [GpsRecorder] is: it runs only
 * while a trip is active, attaches every event to that trip's id, and resets its running state
 * (last fix, last yaw rate, any maneuver mid-debounce) on every trip change.
 *
 * A maneuver spans several samples, so it is not recorded the instant its threshold is crossed:
 * each axis (longitudinal, lateral) tracks a debounce window of [DEBOUNCE_MS] from first crossing
 * and only emits the peak severity seen in that window, once a later fix's timestamp moves past
 * it — see [TripTracking.flushExpired].
 */
class HarshEventDetector(
    private val location: LocationSource,
    private val gyro: GyroSource,
    private val trips: TripRepository,
    private val newId: () -> String = { newUuid() },
) {

    fun start(scope: CoroutineScope) {
        scope.launch {
            var current: TripTracking? = null
            trips.activeTrip.collect { active ->
                current?.end()
                current = active?.let { TripTracking(it, scope).apply { begin() } }
            }
        }
    }

    private inner class TripTracking(private val trip: ActiveTrip, private val scope: CoroutineScope) {
        private var lastFix: GpsFix? = null
        private var lastYawRate: Float = 0f
        private var pendingLon: PendingWindow? = null
        private var pendingCorner: PendingWindow? = null

        private var locJob: Job? = null
        private var gyroJob: Job? = null

        fun begin() {
            gyroJob = scope.launch {
                gyro.yawRate.collect { sample -> lastYawRate = sample.yawRateRadPerSec }
            }
            locJob = scope.launch {
                location.fixes.collect { fix -> process(fix) }
            }
        }

        fun end() {
            locJob?.cancel()
            locJob = null
            gyroJob?.cancel()
            gyroJob = null
        }

        private suspend fun process(fix: GpsFix) {
            if (fix.speedKmh.isNaN()) return
            flushExpired(fix.tsMs)

            val prev = lastFix
            lastFix = fix
            if (prev == null || prev.speedKmh.isNaN()) return

            val dtMs = fix.tsMs - prev.tsMs
            if (dtMs <= 0) return
            val dtS = dtMs / 1_000.0

            val aLon = ((fix.speedKmh - prev.speedKmh) / 3.6) / dtS
            classifyLongitudinal(aLon, startSpeedKmh = prev.speedKmh, endSpeedKmh = fix.speedKmh)
                ?.let { type -> pendingLon = mergeLon(pendingLon, type, abs(aLon), fix) }

            val speedMps = fix.speedKmh / 3.6
            val aLatGyro = speedMps * lastYawRate
            val dBearingRad = normalizeDegrees(fix.bearingDeg - prev.bearingDeg) * DEG_TO_RAD
            val aLatGpsFallback = speedMps * (dBearingRad / dtS)
            val aLat = if (abs(aLatGyro) >= abs(aLatGpsFallback)) aLatGyro else aLatGpsFallback
            if (abs(aLat) >= HARSH_CORNER_THRESHOLD_MS2 && fix.speedKmh >= CORNER_MIN_SPEED_KMH) {
                pendingCorner = mergeCorner(pendingCorner, abs(aLat), fix)
            }
        }

        /** Closes any window whose debounce period has passed, recording it at its peak. */
        private suspend fun flushExpired(tsMs: Long) {
            pendingLon?.let { if (tsMs > it.windowEndMs) { emit(it); pendingLon = null } }
            pendingCorner?.let { if (tsMs > it.windowEndMs) { emit(it); pendingCorner = null } }
        }

        private fun mergeLon(pending: PendingWindow?, type: HarshEventType, severity: Double, fix: GpsFix): PendingWindow {
            if (pending != null && pending.type == type) {
                return if (severity > pending.peak) pending.copy(peak = severity, fix = fix) else pending
            }
            return PendingWindow(type, severity, fix, windowEndMs = fix.tsMs + DEBOUNCE_MS)
        }

        private fun mergeCorner(pending: PendingWindow?, severity: Double, fix: GpsFix): PendingWindow {
            if (pending != null && severity > pending.peak) return pending.copy(peak = severity, fix = fix)
            return pending ?: PendingWindow(HarshEventType.HARSH_CORNER, severity, fix, windowEndMs = fix.tsMs + DEBOUNCE_MS)
        }

        private suspend fun emit(pending: PendingWindow) {
            trips.recordEvent(
                TripEvent(
                    id = newId(),
                    tripId = trip.id,
                    tsMs = pending.fix.tsMs,
                    type = pending.type,
                    severityMs2 = pending.peak,
                    lat = pending.fix.lat,
                    lon = pending.fix.lon,
                ),
            )
        }
    }

    private data class PendingWindow(val type: HarshEventType, val peak: Double, val fix: GpsFix, val windowEndMs: Long)

    private companion object {
        // ~0.3-0.4 g harsh-driving thresholds, common in fleet telematics / driver-behaviour scoring.
        const val HARSH_ACCEL_THRESHOLD_MS2 = 3.0 // ~0.31 g
        const val HARSH_BRAKE_THRESHOLD_MS2 = -3.5 // ~0.36 g
        const val HARSH_CORNER_THRESHOLD_MS2 = 4.0 // ~0.41 g

        const val NEAR_STOP_KMH = 5.0
        const val CORNER_MIN_SPEED_KMH = 15.0

        /** A harsh maneuver spans several samples; one event per window, at its peak. */
        const val DEBOUNCE_MS = 3_000L

        const val DEG_TO_RAD = kotlin.math.PI / 180.0

        fun classifyLongitudinal(aLon: Double, startSpeedKmh: Float, endSpeedKmh: Float): HarshEventType? = when {
            aLon >= HARSH_ACCEL_THRESHOLD_MS2 ->
                if (startSpeedKmh < NEAR_STOP_KMH) HarshEventType.HARSH_START else HarshEventType.HARSH_ACCEL
            aLon <= HARSH_BRAKE_THRESHOLD_MS2 ->
                if (endSpeedKmh < NEAR_STOP_KMH) HarshEventType.HARSH_STOP else HarshEventType.HARSH_BRAKE
            else -> null
        }

        /** Shortest signed angular difference, in degrees, so a wrap through 0/360 does not spike. */
        fun normalizeDegrees(deltaDeg: Float): Double {
            var d = deltaDeg.toDouble() % 360.0
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            return d
        }
    }
}
