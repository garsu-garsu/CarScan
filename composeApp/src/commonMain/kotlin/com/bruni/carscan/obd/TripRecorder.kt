package com.bruni.carscan.obd

import com.bruni.carscan.core.data.ConnectionState
import com.bruni.carscan.core.data.SampleSource
import com.bruni.carscan.core.data.SettingsRepository
import com.bruni.carscan.core.data.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Puts the sample stream on disk — and, far more of the time, does not.
 *
 * `Settings.recordTrips` defaults to **off**, and that is a storage decision rather than a UI one:
 * with it on, every minute the app spends open in someone's car writes to disk, and the gauges
 * need none of it — they read the live stream. So the live path must cost **zero** database
 * writes when recording is off, and that is what [SampleRecorderTest] pins. Not "few writes";
 * none. A recorder that opened a transaction per sample and rolled it back would pass a test that
 * only looked at the rows.
 *
 * [vehicleId] is suspended and called lazily, on the first sample of a trip, for exactly the same
 * reason: resolving it inserts a `vehicle` row, and doing that eagerly would put a write on the
 * live path of a user who never records anything.
 */
class TripRecorder(
    private val source: SampleSource,
    private val trips: TripRepository,
    private val settings: SettingsRepository,
    private val vehicleId: suspend (nowMs: Long) -> String,
    private val nowMs: () -> Long,
) {

    /**
     * Recording follows two things at once, and it needs both: the user's preference, and whether
     * there is anything to record. Starting a trip on a preference alone would open one the moment
     * the app launched, before a single sample could arrive, and the history would fill with empty
     * drives.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(settings.settings, source.health) { current, health ->
                current.recordTrips && health.connection == ConnectionState.CONNECTED
            }
                .distinctUntilChanged()
                .collect { recording -> if (recording) begin() else end() }
        }

        scope.launch {
            source.samples.collect { sample ->
                // The guard, not a `try`. `TripRepository.offer` never suspends — the poller must
                // not block on disk — so this is the only thing between the live path and the
                // writer, and it has to be a plain boolean read.
                if (trips.isRecording) trips.offer(sample)
            }
        }
    }

    private suspend fun begin() {
        if (trips.isRecording) return
        val at = nowMs()
        trips.start(vehicleId(at), at)
    }

    private suspend fun end() {
        if (trips.isRecording) trips.stop(nowMs())
    }
}
