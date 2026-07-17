package com.bruni.carscan.core.units

import com.bruni.carscan.core.model.ObdUnit

/**
 * A number ready to be drawn, and the key for the unit label beside it.
 *
 * [labelKey] is `null` when the value has no display unit of ours (`percent`, `rpm`, `gravity`) —
 * the screen draws [text] and whatever the signal itself is called, and no unit suffix.
 */
data class Readout(val text: String, val labelKey: String?)

/**
 * Converts a decoded sample into the string a gauge draws — **the only supported way to put a
 * converted number on screen.**
 *
 * Conversion and formatting are done together, in one call, on purpose. Split them and the two
 * halves drift: a screen that converts here and then calls `toString()` renders `96.6` for a
 * German driver, while the gauge next to it — formatted properly — renders `96,6`. Both look
 * finished, in the same app, on the same screen, which is exactly why that bug survives a fix.
 *
 * Hold one per (locale, preferences) pair rather than one per call: the platform number formatter
 * inside is expensive to build, and a dashboard formats ~160 numbers a second.
 */
class UnitReadout(
    locale: String? = null,
    private val prefs: UnitPreferences = UnitPreferences.METRIC,
    private val converter: DefaultUnitConverter = DefaultUnitConverter,
) {
    private val numbers = NumberFormatter(locale)

    /**
     * A sample as OBDb decoded it — tagged with its [native] unit — drawn in the user's preferred
     * unit for that quantity.
     *
     * A native unit we cannot map is **not** an error: it is formatted as decoded and labelled with
     * its own name where it has one ([ObdUnit.asIsLabelKey] — rpm, volts, percent). Inventing a
     * conversion for it would be worse than showing it raw, because a made-up number is
     * indistinguishable from a real one on a gauge.
     */
    fun forSample(value: Double, native: ObdUnit, decimals: Int): Readout {
        val from = native.toUnitId()
            ?: return Readout(numbers.format(value, decimals), native.asIsLabelKey)
        return forValue(value, from, decimals)
    }

    /** An absolute value in a known unit, drawn in the preferred unit of that same quantity. */
    fun forValue(value: Double, from: UnitId, decimals: Int): Readout {
        val to = prefs[from.quantity]
        return Readout(numbers.format(converter.convert(value, from, to), decimals), to.labelKey)
    }

    /**
     * A **difference** between two readings — a temperature rise, a speed increase.
     *
     * Goes through [DefaultUnitConverter.convertDelta], so the affine offset is not applied: a rise of
     * 10 °C is drawn as 18, not 50.
     */
    fun forDelta(value: Double, from: UnitId, decimals: Int): Readout {
        val to = prefs[from.quantity]
        return Readout(numbers.format(converter.convertDelta(value, from, to), decimals), to.labelKey)
    }
}
