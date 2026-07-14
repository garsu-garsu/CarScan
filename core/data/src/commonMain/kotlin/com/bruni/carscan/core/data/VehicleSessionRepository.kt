package com.bruni.carscan.core.data

import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.SensorSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The only session surface a feature is allowed to see. `ObdTransport` and
 * `ElmSession` stay internal to :core:obd, so the half-duplex invariant cannot leak
 * into UI code — UI code cannot reach the socket.
 *
 * Two flows, because gauges and charts want opposite things:
 *
 * - [latest] is **conflated**. A gauge redrawing at 60 fps cannot consume a 200 Hz
 *   stream and must not try. A collector that falls behind skips the intermediate
 *   frames and resumes on the newest value, instead of working through a backlog to
 *   render a number that is already stale.
 * - [samples] is every sample, for the live chart and the HUD, which do care about
 *   the shape between two readings.
 *
 * Neither **accumulates**. An eight-hour drive at 200 Hz is roughly 5.8 million
 * samples; a repository that kept them is an out-of-memory crash with a timetable.
 * Windowing is a presentation concern: the live chart owns a ring buffer, and the
 * disk gets 1 Hz aggregates.
 */
interface VehicleSessionRepository {
    /** Newest sample per key. Bounded by the number of signals, not by time. */
    val latest: StateFlow<Map<MetricKey, SensorSample>>

    /** Every sample, as it arrives. */
    val samples: SharedFlow<SensorSample>

    /** What the adapter can do versus what the dashboard is asking of it. */
    val health: StateFlow<SessionHealth>
}

class DefaultVehicleSessionRepository(
    source: SampleSource,
    scope: CoroutineScope,
) : VehicleSessionRepository {

    override val samples: SharedFlow<SensorSample> = source.samples

    override val health: StateFlow<SessionHealth> = source.health

    private val _latest = MutableStateFlow<Map<MetricKey, SensorSample>>(emptyMap())
    override val latest: StateFlow<Map<MetricKey, SensorSample>> = _latest.asStateFlow()

    init {
        scope.launch {
            source.samples.collect { sample ->
                // Replaces the entry for this key. The map is bounded by the key set —
                // a signal that reports for eight hours occupies exactly one slot.
                _latest.update { it + (sample.key to sample) }
            }
        }
    }
}
