package com.bruni.carscan.core.units

import com.bruni.carscan.core.model.ObdUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObdUnitMappingTest {

    private val gb = UnitPreferences.defaultsFor("en-GB")
    private val de = UnitPreferences.defaultsFor("de-DE")

    /** The sample arrives tagged km/h; a British driver must see mph. */
    @Test
    fun `displayUnitFor resolves the native unit through the user's preference`() {
        assertEquals(UnitId.MPH, displayUnitFor(ObdUnit.KILOMETERS_PER_HOUR, gb))
        assertEquals(UnitId.KMH, displayUnitFor(ObdUnit.KILOMETERS_PER_HOUR, de))
    }

    /**
     * And it resolves by *quantity*, not by identity — a signal natively in mph still gets the
     * German user's km/h. The native unit is only ever a starting point.
     */
    @Test
    fun `a natively imperial signal still lands in the user's preferred unit`() {
        assertEquals(UnitId.KMH, displayUnitFor(ObdUnit.MILES_PER_HOUR, de))
        assertEquals(UnitId.CELSIUS, displayUnitFor(ObdUnit.FAHRENHEIT, de))
    }

    @Test
    fun `the mappable natives map to their own unit`() {
        assertEquals(UnitId.KMH, ObdUnit.KILOMETERS_PER_HOUR.toUnitId())
        assertEquals(UnitId.MPH, ObdUnit.MILES_PER_HOUR.toUnitId())
        assertEquals(UnitId.KM, ObdUnit.KILOMETERS.toUnitId())
        assertEquals(UnitId.MILES, ObdUnit.MILES.toUnitId())
        assertEquals(UnitId.KPA, ObdUnit.KILOPASCAL.toUnitId())
        assertEquals(UnitId.BAR, ObdUnit.BARS.toUnitId())
        assertEquals(UnitId.PSI, ObdUnit.PSI.toUnitId())
        assertEquals(UnitId.CELSIUS, ObdUnit.CELSIUS.toUnitId())
        assertEquals(UnitId.FAHRENHEIT, ObdUnit.FAHRENHEIT.toUnitId())
        assertEquals(UnitId.LITRE, ObdUnit.LITERS.toUnitId())
        assertEquals(UnitId.KW, ObdUnit.KILOWATTS.toUnitId())
        assertEquals(UnitId.NM, ObdUnit.NEWTON_METERS.toUnitId())
        assertEquals(UnitId.LB_FT, ObdUnit.POUND_FOOT.toUnitId())
        assertEquals(UnitId.KWH_PER_100KM, ObdUnit.KILOWATT_HOURS_PER_100_KILOMETERS.toUnitId())
        assertEquals(UnitId.MI_PER_KWH, ObdUnit.MILES_PER_KILOWATT_HOUR.toUnitId())
    }

    /**
     * OBDb's `gallons` does not say *which* gallon. We read it as the US gallon, because OBDb is
     * a US-authored database — but that is an assumption, and a wrong one is a 20% error. This
     * test exists to name it, so that if it is ever contradicted by real data the fix has a home.
     */
    @Test
    fun `OBDb gallons is read as the US gallon`() {
        assertEquals(UnitId.US_GALLON, ObdUnit.GALLONS.toUnitId())
    }

    /**
     * The whole point of the nullable return. `gravity`, `coulombs` and `hex` have no display
     * unit here, and inventing one would be worse than showing the raw value: a made-up
     * conversion is indistinguishable from a real one on screen.
     */
    @Test
    fun `an unmappable native unit returns null instead of a guess`() {
        for (exotic in listOf(ObdUnit.GRAVITY, ObdUnit.COULOMBS, ObdUnit.HEX, ObdUnit.SCALAR)) {
            assertNull(exotic.toUnitId(), "$exotic")
            assertNull(displayUnitFor(exotic, gb), "$exotic")
        }
    }

    /**
     * Units that need no conversion — a percentage is a percentage everywhere, and nobody wants
     * rpm in anything but rpm — are unmapped too, and shown as-is.
     */
    @Test
    fun `units that are the same everywhere are not converted`() {
        for (universal in listOf(ObdUnit.PERCENT, ObdUnit.RPM, ObdUnit.VOLTS, ObdUnit.DEGREES)) {
            assertNull(displayUnitFor(universal, gb), "$universal")
        }
    }

    /**
     * …but "not converted" is not "not named". A tachometer still says **rpm** and a battery gauge
     * still says **V**, so these carry a label key even though they have no [UnitId]. Without this
     * every feature would hand-roll its own `ObdUnit`-to-label table, which is the duplication this
     * module exists to prevent.
     */
    @Test
    fun `a unit we do not convert is still named`() {
        assertEquals("unit_rpm", ObdUnit.RPM.asIsLabelKey)
        assertEquals("unit_volts", ObdUnit.VOLTS.asIsLabelKey)
        assertEquals("unit_percent", ObdUnit.PERCENT.asIsLabelKey)
        assertEquals("unit_seconds", ObdUnit.SECONDS.asIsLabelKey)
        assertEquals("unit_grams_per_second", ObdUnit.GRAMS_PER_SECOND.asIsLabelKey)
        assertEquals("unit_liters_per_hour", ObdUnit.LITERS_PER_HOUR.asIsLabelKey)

        assertEquals("unit_degrees", ObdUnit.DEGREES.asIsLabelKey)
        assertEquals("unit_amps", ObdUnit.AMPS.asIsLabelKey)
        assertEquals("unit_milliamps", ObdUnit.MILLIAMPS.asIsLabelKey)
        assertEquals("unit_watts", ObdUnit.WATTS.asIsLabelKey)
        assertEquals("unit_kilowatt_hours", ObdUnit.KILOWATT_HOURS.asIsLabelKey)
        assertEquals("unit_ampere_hours", ObdUnit.AMPERE_HOURS.asIsLabelKey)
        assertEquals("unit_hertz", ObdUnit.HERTZ.asIsLabelKey)
        assertEquals("unit_kiloohms", ObdUnit.KILOOHMS.asIsLabelKey)
        assertEquals("unit_millimeters", ObdUnit.MILLIMETERS.asIsLabelKey)
        assertEquals("unit_minutes", ObdUnit.MINUTES.asIsLabelKey)
        assertEquals("unit_hours", ObdUnit.HOURS.asIsLabelKey)
        assertEquals("unit_milliseconds", ObdUnit.MILLISECONDS.asIsLabelKey)
        assertEquals("unit_kilograms_per_hour", ObdUnit.KILOGRAMS_PER_HOUR.asIsLabelKey)
        assertEquals("unit_meters_per_second_squared", ObdUnit.METERS_PER_SECOND_SQUARED.asIsLabelKey)
        assertEquals("unit_milligrams_per_stroke", ObdUnit.MILLIGRAMS_PER_STROKE.asIsLabelKey)
    }

    /**
     * OBDb's `degrees` is an **angle** — ignition timing advance, steering angle — not a
     * temperature. It gets a bare `°`, and must never resolve to `°C`. The two are one keystroke
     * apart in a `when` and would look entirely plausible on a gauge.
     */
    @Test
    fun `degrees is an angle and never collides with celsius`() {
        assertEquals("unit_degrees", ObdUnit.DEGREES.asIsLabelKey)
        assertNotEquals(UnitId.CELSIUS.labelKey, ObdUnit.DEGREES.asIsLabelKey)
        assertNull(ObdUnit.DEGREES.toUnitId(), "an angle has no temperature preference to honour")
    }

    /**
     * Every native unit that **real vehicles actually declare** must be either convertible or
     * named — otherwise it reaches a gauge as a bare number with nothing after it.
     *
     * This list is not invented: it is every numeric unit found in the 2,825 vendored OBDb
     * signalsets. If OBDb adds one and a vehicle uses it, this test is what says so, instead of a
     * Russian user finding a `2400` with no `об/мин` next to it.
     */
    @Test
    fun `every unit real vehicles report is either convertible or named`() {
        val observedInVendoredSignalsets = listOf(
            ObdUnit.VOLTS, ObdUnit.PERCENT, ObdUnit.CELSIUS, ObdUnit.DEGREES,
            ObdUnit.KILOPASCAL, ObdUnit.PSI, ObdUnit.KILOMETERS_PER_HOUR, ObdUnit.AMPS,
            ObdUnit.KILOMETERS, ObdUnit.SECONDS, ObdUnit.RPM, ObdUnit.GRAMS_PER_SECOND,
            ObdUnit.KILOWATT_HOURS, ObdUnit.NEWTON_METERS, ObdUnit.MINUTES, ObdUnit.MILLISECONDS,
            ObdUnit.MILLIAMPS, ObdUnit.MILES, ObdUnit.HOURS, ObdUnit.MILLIMETERS, ObdUnit.WATTS,
            ObdUnit.MILLIGRAMS_PER_STROKE, ObdUnit.METERS_PER_SECOND_SQUARED,
            ObdUnit.LITERS_PER_HOUR, ObdUnit.KILOGRAMS_PER_HOUR, ObdUnit.HERTZ,
            ObdUnit.AMPERE_HOURS, ObdUnit.LITERS, ObdUnit.KILOWATTS, ObdUnit.KILOOHMS,
        )

        for (native in observedInVendoredSignalsets) {
            val named = native.toUnitId() != null || native.asIsLabelKey != null
            assertTrue(named, "$native reaches a gauge as a bare number with no unit beside it")
        }
    }

    /**
     * A convertible unit has no as-is label: its label depends on what the user chose to see it
     * in, so asking for one without consulting the preferences is always a bug. Ask
     * [displayUnitFor] instead.
     */
    @Test
    fun `a convertible unit has no as-is label`() {
        assertNull(ObdUnit.KILOMETERS_PER_HOUR.asIsLabelKey)
        assertNull(ObdUnit.CELSIUS.asIsLabelKey)
    }

    /** And a unit that is not a quantity at all is neither converted nor named. */
    @Test
    fun `a non-physical unit has no label at all`() {
        for (exotic in listOf(ObdUnit.HEX, ObdUnit.SCALAR, ObdUnit.GRAVITY, ObdUnit.ASCII)) {
            assertNull(exotic.asIsLabelKey, "$exotic")
        }
    }

    /**
     * Kelvin is a temperature we could technically convert, and kWh/100mi is an economy we
     * could technically convert — but neither has a [UnitId], because neither is a unit any
     * driver wants to read. Rather than half-support them, they are shown as-is.
     */
    @Test
    fun `a native unit with no display counterpart is left alone`() {
        assertNull(ObdUnit.KELVIN.toUnitId())
        assertNull(ObdUnit.KILOWATT_HOURS_PER_100_MILES.toUnitId())
        assertNull(ObdUnit.METERS_PER_SECOND.toUnitId())
        assertNull(ObdUnit.LITERS_PER_HOUR.toUnitId())
    }

    /** Every native unit we *do* map must be convertible from, or the mapping is a lie. */
    @Test
    fun `every mapped native unit can actually be converted to the preferred unit`() {
        for (native in ObdUnit.entries) {
            val from = native.toUnitId() ?: continue
            val to = displayUnitFor(native, gb)!!
            DefaultUnitConverter.convert(1.0, from, to)
            assertEquals(from.quantity, to.quantity, "$native")
        }
    }
}
