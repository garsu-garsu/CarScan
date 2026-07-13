package com.bruni.carscan.core.model

/**
 * One decoded reading, in the unit OBDb declared it in.
 *
 * Values travel and are stored natively — km/h, celsius, kilopascal — and are
 * converted to the user's preferred unit exactly once, when they are rendered.
 * Because every sample carries its own [unit], a later change to the unit tables
 * cannot retroactively corrupt recorded history.
 */
data class SensorSample(
    val signalId: String,
    val key: MetricKey,
    val value: DecodedValue,
    val unit: ObdUnit?,
    val timestampMs: Long,
    /** The ECU that answered, e.g. "7EC". Two ECUs can report the same signal. */
    val ecu: String? = null,
    /** True when the signal is flagged experimental for this vehicle/model year. */
    val experimental: Boolean = false,
)

/**
 * What a signal decodes to. Not everything on a CAN bus is a number: `fmt.map`
 * turns a raw integer into a labeled state, and the `ascii`/`hex` units carry
 * things like the VIN.
 */
sealed interface DecodedValue {
    data class Numeric(val value: Double) : DecodedValue

    /** From `fmt.map`. [key] is the raw integer that was matched. */
    data class Enumerated(val key: Int, val label: String, val description: String? = null) : DecodedValue

    /** From the offon / onoff / yesno / noyes units. */
    data class Bool(val value: Boolean) : DecodedValue

    /** From the ascii / hex units. */
    data class Text(val value: String) : DecodedValue
}

/** The numeric value, or null if this sample is not a number. Convenience for gauges and charts. */
val DecodedValue.asDoubleOrNull: Double?
    get() = when (this) {
        is DecodedValue.Numeric -> value
        is DecodedValue.Bool -> if (value) 1.0 else 0.0
        is DecodedValue.Enumerated -> key.toDouble()
        is DecodedValue.Text -> null
    }
