package com.bruni.carscan.core.units

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class UnitConverterTest {

    private val convert = DefaultUnitConverter

    // ---- Trap 1: temperature is affine, so a difference does not convert like a value ----

    /**
     * The whole reason [DefaultUnitConverter.convertDelta] exists as a separate function. 10 °C as a
     * *reading* is 50 °F; 10 °C as a *difference* is 18 °F. Feeding a ΔT to the absolute
     * converter is the bug this API is shaped to make unspellable.
     */
    @Test
    fun `a temperature difference of 10 C is 18 F, not 50 F`() {
        assertEquals(18.0, convert.convertDelta(10.0, UnitId.CELSIUS, UnitId.FAHRENHEIT), 1e-9)
        assertNotEquals(
            50.0,
            convert.convertDelta(10.0, UnitId.CELSIUS, UnitId.FAHRENHEIT),
            "the 32° offset must not be applied to a difference",
        )
    }

    @Test
    fun `a temperature reading of 10 C is 50 F`() {
        assertEquals(50.0, convert.convert(10.0, UnitId.CELSIUS, UnitId.FAHRENHEIT), 1e-9)
    }

    /** Coolant at 90 °C is 194 °F — the reading a US user must see on the gauge. */
    @Test
    fun `absolute and delta temperature conversions disagree by exactly the offset`() {
        val absolute = convert.convert(90.0, UnitId.CELSIUS, UnitId.FAHRENHEIT)
        val delta = convert.convertDelta(90.0, UnitId.CELSIUS, UnitId.FAHRENHEIT)
        assertEquals(194.0, absolute, 1e-9)
        assertEquals(162.0, delta, 1e-9)
        assertEquals(32.0, absolute - delta, 1e-9)
    }

    /** Every other quantity is scale-only, so there the two functions must agree. */
    @Test
    fun `for a scale-only quantity a delta converts like a value`() {
        assertEquals(
            convert.convert(100.0, UnitId.KMH, UnitId.MPH),
            convert.convertDelta(100.0, UnitId.KMH, UnitId.MPH),
            1e-9,
        )
    }

    // ---- Trap 2: the imperial gallon is not the US gallon ----

    /**
     * One physical consumption — 8 L/100km — is 29.4 mpg in the US and 35.3 mpg in the UK.
     * They differ by ~20% because UK_GALLON (4.54609 L) is ~20% bigger than US_GALLON
     * (3.785411784 L). Showing a UK driver the US figure is the classic one-star review.
     */
    @Test
    fun `UK and US MPG differ by about 20 percent for the same physical consumption`() {
        val lPer100km = 8.0
        val mpgUs = convert.convert(lPer100km, UnitId.L_PER_100KM, UnitId.MPG_US)
        val mpgUk = convert.convert(lPer100km, UnitId.L_PER_100KM, UnitId.MPG_UK)

        assertEquals(29.4018, mpgUs, 1e-4)
        assertEquals(35.3101, mpgUk, 1e-4)

        val ratio = mpgUk / mpgUs
        assertTrue(ratio in 1.19..1.21, "expected a ~20% gap, got a ratio of $ratio")
    }

    @Test
    fun `the two gallons are not the same volume`() {
        val oneUsGallon = convert.convert(1.0, UnitId.US_GALLON, UnitId.LITRE)
        val oneUkGallon = convert.convert(1.0, UnitId.UK_GALLON, UnitId.LITRE)
        assertEquals(3.785411784, oneUsGallon, 1e-9)
        assertEquals(4.54609, oneUkGallon, 1e-9)
    }

    /** MPG converts directly between the two gallons without a detour through L/100km. */
    @Test
    fun `MPG US converts straight to MPG UK`() {
        assertEquals(
            36.0,
            convert.convert(30.0, UnitId.MPG_US, UnitId.MPG_UK),
            0.05,
        )
    }

    // ---- Anchors for the remaining quantities ----

    @Test
    fun `known anchors convert correctly`() {
        assertEquals(62.1371, convert.convert(100.0, UnitId.KMH, UnitId.MPH), 1e-4)
        assertEquals(62.1371, convert.convert(100.0, UnitId.KM, UnitId.MILES), 1e-4)
        assertEquals(2.0, convert.convert(200.0, UnitId.KPA, UnitId.BAR), 1e-9)
        assertEquals(29.0075, convert.convert(200.0, UnitId.KPA, UnitId.PSI), 1e-4)
        assertEquals(100.0, convert.convert(100.0, UnitId.KW, UnitId.KW), 1e-9)
        assertEquals(134.102, convert.convert(100.0, UnitId.KW, UnitId.HP), 1e-3)
        assertEquals(135.962, convert.convert(100.0, UnitId.KW, UnitId.PS), 1e-3)
        assertEquals(73.7562, convert.convert(100.0, UnitId.NM, UnitId.LB_FT), 1e-4)
        assertEquals(12.5, convert.convert(8.0, UnitId.L_PER_100KM, UnitId.KM_PER_L), 1e-9)
        assertEquals(3.1069, convert.convert(20.0, UnitId.KWH_PER_100KM, UnitId.MI_PER_KWH), 1e-4)
    }

    // ---- Round-trips, every pair, every quantity ----

    /**
     * `convert(convert(x, A, B), B, A) == x` for every ordered pair inside every quantity.
     * This is what catches a constant that is right in one direction and wrong in the other —
     * the failure mode of a hand-rolled converter that multiplies where it should divide.
     */
    @Test
    fun `every pair in every quantity round-trips`() {
        val probes = listOf(1.0, 7.5, 42.0, 137.25)
        for (quantity in Quantity.entries) {
            val units = UnitId.entries.filter { it.quantity == quantity }
            for (from in units) {
                for (to in units) {
                    for (x in probes) {
                        val there = convert.convert(x, from, to)
                        val back = convert.convert(there, to, from)
                        assertEquals(
                            x,
                            back,
                            1e-9 * maxOf(1.0, x),
                            "$x $from -> $to -> $from came back as $back",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `converting a unit to itself is the identity`() {
        for (unit in UnitId.entries) {
            assertEquals(13.8, convert.convert(13.8, unit, unit), 1e-12, "$unit")
            if (!unit.isInverse) {
                assertEquals(13.8, convert.convertDelta(13.8, unit, unit), 1e-12, "$unit")
            }
        }
    }

    // ---- Conversions that must refuse rather than invent a number ----

    @Test
    fun `converting across quantities throws instead of returning nonsense`() {
        assertFailsWith<IllegalArgumentException> {
            convert.convert(100.0, UnitId.KMH, UnitId.CELSIUS)
        }
    }

    /**
     * Litres and kilowatt-hours are not interconvertible without a fuel energy density, which
     * this layer does not have and must not guess. They are separate quantities precisely so
     * that this call cannot compile into a plausible wrong number.
     */
    @Test
    fun `fuel consumption does not convert to electric consumption`() {
        assertFailsWith<IllegalArgumentException> {
            convert.convert(8.0, UnitId.L_PER_100KM, UnitId.KWH_PER_100KM)
        }
    }

    /**
     * A *difference* of MPG or km/L is meaningless: the unit is an inverse, so subtracting two
     * readings does not commute with the conversion. Rather than return a number nobody can
     * interpret, refuse.
     */
    @Test
    fun `a delta of an inverse consumption unit throws`() {
        assertFailsWith<IllegalArgumentException> {
            convert.convertDelta(5.0, UnitId.MPG_US, UnitId.MPG_UK)
        }
        assertFailsWith<IllegalArgumentException> {
            convert.convertDelta(5.0, UnitId.L_PER_100KM, UnitId.KM_PER_L)
        }
        assertFailsWith<IllegalArgumentException> {
            convert.convertDelta(5.0, UnitId.KWH_PER_100KM, UnitId.MI_PER_KWH)
        }
    }

    /** But a delta of L/100km against itself is fine — it is a linear unit. */
    @Test
    fun `a delta of a linear consumption unit is allowed`() {
        assertEquals(
            2.0,
            convert.convertDelta(2.0, UnitId.L_PER_100KM, UnitId.L_PER_100KM),
            1e-12,
        )
    }
}
