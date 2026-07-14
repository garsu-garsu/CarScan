package com.bruni.carscan.core.units

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The formatter exists because six of the eight shipping locales write `13,8`, not `13.8`.
 *
 * So every assertion here that matters is a *comparison between locales*. A test that only
 * formats in one locale proves nothing — that is precisely how the hard-coded `'.'` shipped
 * in the first place and passed its tests.
 */
class NumberFormatterTest {

    /** en · ko write a dot. ru · de · pl · pt-BR · es · uk write a comma. */
    private val dotLocales = listOf("en", "ko")
    private val commaLocales = listOf("ru", "de", "pl", "pt-BR", "es", "uk")

    @Test
    fun theDecimalSeparatorDiffersBetweenLocales() {
        for (tag in dotLocales) {
            assertEquals("13.8", NumberFormatter(tag).format(13.8, decimals = 1), "locale $tag")
        }
        for (tag in commaLocales) {
            assertEquals("13,8", NumberFormatter(tag).format(13.8, decimals = 1), "locale $tag")
        }
    }

    @Test
    fun everyShippingLocaleFormatsTheSameNumberDifferentlyFromAtLeastOneOther() {
        // The whole point, stated as one assertion: en and de must not agree.
        val en = NumberFormatter("en").format(13.8, decimals = 1)
        val de = NumberFormatter("de").format(13.8, decimals = 1)
        val ru = NumberFormatter("ru").format(13.8, decimals = 1)

        assertEquals("13.8", en)
        assertEquals("13,8", de)
        assertEquals("13,8", ru)
    }

    @Test
    fun groupingIsNeverUsed() {
        // Critical, not cosmetic. German groups thousands with a *dot*: a grouped 1726 rpm
        // renders as "1.726", which a driver reads as 1.7 rpm. The gauge contract is "1726".
        assertEquals("1726", NumberFormatter("en").format(1726.0, decimals = 0))
        assertEquals("1726", NumberFormatter("de").format(1726.0, decimals = 0))
        assertEquals("1726", NumberFormatter("ru").format(1726.0, decimals = 0))
        assertEquals("8000.0", NumberFormatter("en").format(8000.0, decimals = 1))
        assertEquals("8000,0", NumberFormatter("de").format(8000.0, decimals = 1))
    }

    // --- the rounding semantics the gauge contract depends on ------------------

    @Test
    fun roundsHalfUpRatherThanTruncating() {
        // A formatter that truncates breaks GaugeSpec.decimals: 1.6 must read 2, not 1.
        val en = NumberFormatter("en")
        assertEquals("2", en.format(1.6, decimals = 0))
        assertEquals("0.3", en.format(0.25, decimals = 1))
        assertEquals("1726", en.format(1726.4, decimals = 0))
        assertEquals("1727", en.format(1726.5, decimals = 0))
    }

    @Test
    fun tiesRoundAwayFromZeroInEveryLocale() {
        // HALF_UP, not HALF_EVEN: 2.5 is 3, never 2. Both JVM and NSNumberFormatter default to
        // banker's rounding, so both actuals have to say so explicitly.
        for (tag in dotLocales + commaLocales) {
            assertEquals("3", NumberFormatter(tag).format(2.5, decimals = 0), "locale $tag")
            assertEquals("-3", NumberFormatter(tag).format(-2.5, decimals = 0), "locale $tag")
        }
    }

    @Test
    fun padsToExactlyTheRequestedNumberOfDecimals() {
        // 13.8 at two places is "13.80" — the gauge contract, and Double.toString cannot do it.
        assertEquals("13.80", NumberFormatter("en").format(13.8, decimals = 2))
        assertEquals("13,80", NumberFormatter("de").format(13.8, decimals = 2))
        assertEquals("5.00", NumberFormatter("en").format(5.0, decimals = 2))
        assertEquals("5,00", NumberFormatter("ru").format(5.0, decimals = 2))
    }

    @Test
    fun keepsTheSignOnNegativeValues() {
        // Intake air temperature goes below zero and the minus must survive the rounding.
        assertEquals("-7", NumberFormatter("en").format(-7.2, decimals = 0))
        assertEquals("-0,5", NumberFormatter("de").format(-0.5, decimals = 1))
    }

    @Test
    fun zeroDecimalsLeavesNoSeparatorAtAll() {
        assertEquals("40", NumberFormatter("de").format(40.0, decimals = 0))
        assertEquals("-40", NumberFormatter("ru").format(-40.0, decimals = 0))
    }

    @Test
    fun anUnknownOrNullLocaleStillFormats() {
        // A device set to a locale we do not ship must render a number, not crash.
        assertEquals(3, NumberFormatter(null).format(1.5, decimals = 1).length)
        assertEquals(3, NumberFormatter("zz").format(1.5, decimals = 1).length)
    }
}
