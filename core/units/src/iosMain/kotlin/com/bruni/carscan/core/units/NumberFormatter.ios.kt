package com.bruni.carscan.core.units

import platform.Foundation.NSLocale
import platform.Foundation.NSNumber
import platform.Foundation.NSNumberFormatter
import platform.Foundation.NSNumberFormatterDecimalStyle
import platform.Foundation.NSNumberFormatterRoundHalfUp
import platform.Foundation.currentLocale
import platform.Foundation.numberWithDouble

/**
 * **Never compiled.** Apple targets are only registered on a macOS host, so this file has not
 * been through a compiler on this project. It is written from the `NSNumberFormatter` docs and
 * should be expected to need a fix on first contact with Xcode.
 */
actual class NumberFormatter actual constructor(locale: String?) {

    private val format = NSNumberFormatter().apply {
        numberStyle = NSNumberFormatterDecimalStyle
        // NSNumberFormatter groups by default, and Germany groups with a dot: 1726 rpm would
        // render "1.726". And its default rounding is banker's, which would turn 2.5 into 2.
        usesGroupingSeparator = false
        roundingMode = NSNumberFormatterRoundHalfUp
        // An unknown identifier yields a locale that still formats, so there is nothing to guard.
        this.locale = if (locale == null) NSLocale.currentLocale else NSLocale(locale)
    }

    actual fun format(value: Double, decimals: Int): String {
        val places = decimals.coerceAtLeast(0).toULong()
        format.minimumFractionDigits = places
        format.maximumFractionDigits = places
        return format.stringFromNumber(NSNumber.numberWithDouble(value)) ?: value.toString()
    }
}
