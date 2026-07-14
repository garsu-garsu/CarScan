package com.bruni.carscan.core.data

import com.bruni.carscan.core.model.SensorSample
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where decoded samples come from. Declared here, in :core:data, and satisfied by
 * `PidScheduler` in :core:obd — the dependency points *inwards*.
 *
 * That inversion is the reason :core:data does not depend on :core:obd at all. The
 * repository needs the shape of the sample stream, not the ELM327 machinery behind
 * it. As a result the whole app above the transport can be driven by the ELM327
 * emulator (or by a fixture) without touching a line of repository code, and the
 * half-duplex invariant stays sealed inside :core:obd where it cannot leak.
 *
 * [health] is [SessionHealth] rather than :core:obd's `PollerHealth` for exactly the
 * same reason; :composeApp maps one onto the other where it binds the two.
 */
interface SampleSource {
    val samples: SharedFlow<SensorSample>
    val health: StateFlow<SessionHealth>
}
