package com.bruni.carscan.core.units

import com.bruni.carscan.core.model.ObdUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The boundary a screen actually consumes: sample in, drawn string out.
 *
 * It exists so that conversion and formatting cannot be done separately, because doing them
 * separately is how you ship a speedometer reading `96.6` next to a coolant gauge reading `96,6`
 * — in German, in the same app, on the same screen. Both halves look finished. That is why the
 * assertions below are **comparisons between locales**, not single-locale spot checks.
 */
class UnitReadoutTest {

    /**
     * The test the whole file is for. A *converted* value — 60 mph off a US-market signal, shown
     * to a German driver as 96.6 km/h — must be written with a comma. Formatting the raw Double
     * with `toString()` anywhere on this path makes it `96.6` and this goes red.
     */
    @Test
    fun `a converted readout is written the way the locale writes numbers`() {
        val german = UnitReadout(locale = "de", prefs = UnitPreferences.METRIC)
        val english = UnitReadout(locale = "en", prefs = UnitPreferences.METRIC)

        val de = german.forSample(60.0, ObdUnit.MILES_PER_HOUR, decimals = 1)
        val en = english.forSample(60.0, ObdUnit.MILES_PER_HOUR, decimals = 1)

        assertEquals("96,6", de.text)
        assertEquals("96.6", en.text)
        assertNotEquals(de.text, en.text, "the decimal separator must not be hard-coded")

        // …and it really was converted, not just reformatted.
        assertEquals(UnitId.KMH.labelKey, de.labelKey)
    }

    /** The same value, converted the other way, for a British driver. */
    @Test
    fun `a British driver gets mph from a km per hour signal`() {
        val readout = UnitReadout(locale = "en", prefs = UnitPreferences.defaultsFor("en-GB"))
        val speed = readout.forSample(100.0, ObdUnit.KILOMETERS_PER_HOUR, decimals = 1)

        assertEquals("62.1", speed.text)
        assertEquals(UnitId.MPH.labelKey, speed.labelKey)
    }

    /**
     * Grouping must not appear even here. German groups thousands with a dot, so a grouped
     * 1726 rpm reads as "1.726" — 1.7 to anyone glancing at a tachometer.
     */
    @Test
    fun `an unconvertible signal is still formatted for the locale, and never grouped`() {
        val readout = UnitReadout(locale = "de", prefs = UnitPreferences.METRIC)
        val rpm = readout.forSample(1726.0, ObdUnit.RPM, decimals = 0)

        assertEquals("1726", rpm.text)
        // Not converted, but still named: a tachometer says "rpm".
        assertEquals("unit_rpm", rpm.labelKey)
    }

    @Test
    fun `a percentage is localized and still carries its label`() {
        val readout = UnitReadout(locale = "de", prefs = UnitPreferences.METRIC)
        val load = readout.forSample(13.8, ObdUnit.PERCENT, decimals = 1)

        assertEquals("13,8", load.text)
        assertEquals("unit_percent", load.labelKey)
    }

    /** A value that is not a physical quantity gets a number and no unit at all. */
    @Test
    fun `a non-physical value carries no label`() {
        val readout = UnitReadout(locale = "de", prefs = UnitPreferences.METRIC)
        assertNull(readout.forSample(1.0, ObdUnit.SCALAR, decimals = 0).labelKey)
    }

    /** Coolant off a US-market ECU, drawn for a German driver: 194 °F is 90 °C. */
    @Test
    fun `an absolute temperature is converted, not merely relabelled`() {
        val readout = UnitReadout(locale = "de", prefs = UnitPreferences.METRIC)
        val coolant = readout.forSample(194.0, ObdUnit.FAHRENHEIT, decimals = 0)

        assertEquals("90", coolant.text)
        assertEquals(UnitId.CELSIUS.labelKey, coolant.labelKey)
    }

    /**
     * And a *difference* goes through the delta path, so the 32° offset is not applied: a rise of
     * 10 °C is a rise of 18 °F, not 50 °F.
     */
    @Test
    fun `a delta readout does not pick up the affine offset`() {
        val readout = UnitReadout(locale = "en", prefs = UnitPreferences.defaultsFor("en-US"))
        val rise = readout.forDelta(10.0, UnitId.CELSIUS, decimals = 0)

        assertEquals("18", rise.text)
        assertNotEquals("50", rise.text)
        assertEquals(UnitId.FAHRENHEIT.labelKey, rise.labelKey)
    }

    /** A value already in a known unit, drawn in whatever the user prefers for that quantity. */
    @Test
    fun `forValue converts into the preferred unit of that quantity`() {
        val readout = UnitReadout(locale = "en", prefs = UnitPreferences.defaultsFor("en-US"))
        val boost = readout.forValue(200.0, UnitId.KPA, decimals = 1)

        assertEquals("29.0", boost.text)
        assertEquals(UnitId.PSI.labelKey, boost.labelKey)
    }
}
