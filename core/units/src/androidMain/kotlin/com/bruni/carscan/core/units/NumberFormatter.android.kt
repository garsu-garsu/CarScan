package com.bruni.carscan.core.units

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * Identical to the `jvmMain` actual, on purpose — see the note there.
 */
actual class NumberFormatter actual constructor(locale: String?) {

    private val format: NumberFormat = NumberFormat.getNumberInstance(
        if (locale == null) Locale.getDefault() else Locale.forLanguageTag(locale),
    ).apply {
        isGroupingUsed = false
        roundingMode = RoundingMode.HALF_UP
    }

    actual fun format(value: Double, decimals: Int): String {
        val places = decimals.coerceAtLeast(0)
        format.minimumFractionDigits = places
        format.maximumFractionDigits = places
        return format.format(value)
    }
}
