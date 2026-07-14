package com.bruni.carscan.core.units

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * Identical to the `androidMain` actual, on purpose.
 *
 * There is no source set shared by the JVM and Android targets in this build, and adding one
 * means editing the convention plugin. Twenty lines of duplication is cheaper than that; if a
 * `javaMain` source set ever appears, both files collapse into it unchanged.
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
