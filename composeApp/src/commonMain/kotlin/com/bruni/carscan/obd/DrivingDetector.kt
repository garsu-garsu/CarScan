package com.bruni.carscan.obd

import com.bruni.carscan.core.data.AdapterRepository
import com.bruni.carscan.core.data.ConnectionState
import com.bruni.carscan.core.data.LocationSource
import com.bruni.carscan.core.data.ObdConnector
import com.bruni.carscan.core.data.SampleSource
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.TripRepository
import com.bruni.carscan.core.transport.DiscoveredAdapter
import com.bruni.carscan.core.transport.TransportKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Detects driving from GPS speed alone, and — only when nothing else is recording — records a
 * GPS-only trip for it. This is what gives a car with no scanner plugged in (or one the app has
 * not reconnected to yet) a trip history at all.
 *
 * It never touches an OBD trip. `TripRecorder` owns those; this class owns only the GPS-only
 * trips it starts itself, tracked by [ownedTripId], and guards every start against one already
 * being active — see [onDrivingStarted].
 *
 * The state machine is debounced on the *stop* side only: a red light or a stop sign must not end
 * a trip, so "stopped" only fires after [STOP_DEBOUNCE_MS] of sustained low speed. There is no
 * debounce on the *start* side — the first fix at or above the threshold is enough to mean
 * "driving", because a false start just costs one attempted (and immediately reconnectable, or
 * short) trip, while a false stop costs the rest of an actual drive.
 */
class DrivingDetector(
    private val location: LocationSource,
    private val source: SampleSource,
    private val connector: ObdConnector,
    private val adapters: AdapterRepository,
    private val trips: TripRepository,
    private val settings: SettingsRepository,
    private val nowMs: () -> Long,
) {

    private var isDriving = false
    private var stopJob: Job? = null

    /** The GPS-only trip this detector started, if any. Never an OBD trip — see the class KDoc. */
    private var ownedTripId: String? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(location.fixes, source.health) { fix, health -> fix to health }
                .collect { (fix, health) ->
                    val threshold = settings.settings.first().autoDriveDetectSpeedKmh
                    if (fix.speedKmh >= threshold) {
                        stopJob?.cancel()
                        stopJob = null
                        if (!isDriving) {
                            isDriving = true
                            onDrivingStarted(health.connection)
                        }
                    } else if (isDriving && stopJob == null) {
                        stopJob = launch {
                            delay(STOP_DEBOUNCE_MS)
                            isDriving = false
                            endOwnedTrip()
                        }
                    }

                    // OBD reconnecting mid-trip hands the trip off to TripRecorder, whether that
                    // reconnect was AutoConnector's, a manual one, or the attempt just below.
                    if (health.connection == ConnectionState.CONNECTED) endOwnedTrip()
                }
        }
    }

    private suspend fun onDrivingStarted(connection: ConnectionState) {
        if (connection == ConnectionState.CONNECTED) return // TripRecorder owns the OBD trip.
        if (trips.isRecording) return // Never start a second trip.

        val remembered = adapters.lastUsed()
        if (remembered != null) {
            val target = DiscoveredAdapter(
                kind = TransportKind.valueOf(remembered.kind),
                address = remembered.address,
                name = remembered.name,
            )
            try {
                connector.connect(target, remembered)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Swallowed on purpose, same as AutoConnector — falls through to the GPS-only path.
            }
        }

        if (source.health.value.connection != ConnectionState.CONNECTED && !trips.isRecording) {
            ownedTripId = trips.start(vehicleId = null, nowMs(), source = "GPS")
        }
    }

    private suspend fun endOwnedTrip() {
        val id = ownedTripId ?: return
        trips.stop(nowMs())
        ownedTripId = null
    }

    private companion object {
        /** A red light must not end a trip — see the class KDoc. */
        const val STOP_DEBOUNCE_MS = 60_000L
    }
}
