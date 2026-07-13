package com.bruni.carscan.core.data

import com.bruni.carscan.core.model.SuggestedMetric
import kotlinx.coroutines.flow.StateFlow

/**
 * The only session surface features are allowed to see. ObdTransport and
 * ElmSession stay internal to :core:obd, so the half-duplex invariant cannot
 * leak out of the protocol layer.
 */
interface VehicleSessionRepository {
    val latest: StateFlow<Map<SuggestedMetric, Double>>
}
