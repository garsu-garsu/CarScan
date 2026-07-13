package com.bruni.carscan.core.model

/**
 * The canonical quantity a signal represents, independent of which PID or which
 * vehicle produced it. Gauges and the dashboard are keyed on this, never on a
 * raw signal id.
 */
enum class SuggestedMetric {
    SPEED,
    ENGINE_RPM,
    COOLANT_TEMPERATURE,
    STATE_OF_CHARGE,
}
