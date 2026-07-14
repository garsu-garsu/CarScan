package com.bruni.carscan.core.units

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class UnitPreferencesTest {

    /**
     * **The row this whole design exists for.** A British driver reads speed in mph, odometer
     * in miles, buys fuel in litres, and quotes economy in imperial MPG — all at once, and
     * wants temperature in °C while doing it. No `isMetric` boolean can express that. If
     * `UnitPreferences` ever collapses back into a single switch, this test is what breaks.
     */
    @Test
    fun `en-GB is miles and litres and imperial MPG at the same time`() {
        val gb = UnitPreferences.defaultsFor("en-GB")

        assertEquals(UnitId.MPH, gb[Quantity.SPEED])
        assertEquals(UnitId.MILES, gb[Quantity.DISTANCE])
        assertEquals(UnitId.LITRE, gb[Quantity.VOLUME])
        assertEquals(UnitId.MPG_UK, gb[Quantity.CONSUMPTION])
        assertEquals(UnitId.CELSIUS, gb[Quantity.TEMPERATURE])
        assertEquals(UnitId.PSI, gb[Quantity.PRESSURE])
    }

    /** And it is emphatically not the US row, though both are "imperial". */
    @Test
    fun `en-GB is not en-US`() {
        val gb = UnitPreferences.defaultsFor("en-GB")
        val us = UnitPreferences.defaultsFor("en-US")

        assertNotEquals(us[Quantity.CONSUMPTION], gb[Quantity.CONSUMPTION])
        assertNotEquals(us[Quantity.VOLUME], gb[Quantity.VOLUME])
        assertNotEquals(us[Quantity.TEMPERATURE], gb[Quantity.TEMPERATURE])
        assertEquals(us[Quantity.SPEED], gb[Quantity.SPEED])
    }

    @Test
    fun `en-US is mph miles psi Fahrenheit US gallons and US MPG`() {
        val us = UnitPreferences.defaultsFor("en-US")

        assertEquals(UnitId.MPH, us[Quantity.SPEED])
        assertEquals(UnitId.MILES, us[Quantity.DISTANCE])
        assertEquals(UnitId.PSI, us[Quantity.PRESSURE])
        assertEquals(UnitId.FAHRENHEIT, us[Quantity.TEMPERATURE])
        assertEquals(UnitId.US_GALLON, us[Quantity.VOLUME])
        assertEquals(UnitId.MPG_US, us[Quantity.CONSUMPTION])
    }

    /** Canada is metric — except tyre pressure, which every Canadian gauge reads in psi. */
    @Test
    fun `en-CA is metric but with psi`() {
        val ca = UnitPreferences.defaultsFor("en-CA")

        assertEquals(UnitId.KMH, ca[Quantity.SPEED])
        assertEquals(UnitId.KM, ca[Quantity.DISTANCE])
        assertEquals(UnitId.PSI, ca[Quantity.PRESSURE])
        assertEquals(UnitId.CELSIUS, ca[Quantity.TEMPERATURE])
        assertEquals(UnitId.LITRE, ca[Quantity.VOLUME])
        assertEquals(UnitId.L_PER_100KM, ca[Quantity.CONSUMPTION])
    }

    /** Korea quotes economy as km/L, not L/100km — and tyre pressure in psi. */
    @Test
    fun `ko-KR is km per litre and psi`() {
        val kr = UnitPreferences.defaultsFor("ko-KR")

        assertEquals(UnitId.KMH, kr[Quantity.SPEED])
        assertEquals(UnitId.KM_PER_L, kr[Quantity.CONSUMPTION])
        assertEquals(UnitId.PSI, kr[Quantity.PRESSURE])
        assertEquals(UnitId.LITRE, kr[Quantity.VOLUME])
    }

    @Test
    fun `the continental European locales are fully metric with bar`() {
        for (tag in listOf("de-DE", "pl-PL", "es-ES", "pt-PT", "uk-UA", "ru-RU")) {
            val prefs = UnitPreferences.defaultsFor(tag)
            assertEquals(UnitId.KMH, prefs[Quantity.SPEED], tag)
            assertEquals(UnitId.KM, prefs[Quantity.DISTANCE], tag)
            assertEquals(UnitId.BAR, prefs[Quantity.PRESSURE], tag)
            assertEquals(UnitId.CELSIUS, prefs[Quantity.TEMPERATURE], tag)
            assertEquals(UnitId.LITRE, prefs[Quantity.VOLUME], tag)
            assertEquals(UnitId.L_PER_100KM, prefs[Quantity.CONSUMPTION], tag)
        }
    }

    /** pt-BR is Brazil, not Portugal, and must not be routed by language alone. */
    @Test
    fun `an unknown region falls back to metric rather than throwing`() {
        val br = UnitPreferences.defaultsFor("pt-BR")
        assertEquals(UnitId.KMH, br[Quantity.SPEED])
        assertEquals(UnitId.CELSIUS, br[Quantity.TEMPERATURE])
    }

    @Test
    fun `a bare language tag with no region is metric`() {
        assertEquals(UnitId.KMH, UnitPreferences.defaultsFor("en")[Quantity.SPEED])
        assertEquals(UnitId.KMH, UnitPreferences.defaultsFor("")[Quantity.SPEED])
    }

    @Test
    fun `an explicit region argument overrides the region in the tag`() {
        val prefs = UnitPreferences.defaultsFor("en-US", region = "GB")
        assertEquals(UnitId.MPG_UK, prefs[Quantity.CONSUMPTION])
    }

    @Test
    fun `region matching is case-insensitive and accepts underscores`() {
        assertEquals(UnitId.MPG_UK, UnitPreferences.defaultsFor("en_gb")[Quantity.CONSUMPTION])
    }

    /** EV economy follows the distance preference: miles regions read mi/kWh. */
    @Test
    fun `electric consumption defaults follow the distance unit`() {
        assertEquals(
            UnitId.MI_PER_KWH,
            UnitPreferences.defaultsFor("en-GB")[Quantity.ENERGY_CONSUMPTION],
        )
        assertEquals(
            UnitId.MI_PER_KWH,
            UnitPreferences.defaultsFor("en-US")[Quantity.ENERGY_CONSUMPTION],
        )
        assertEquals(
            UnitId.KWH_PER_100KM,
            UnitPreferences.defaultsFor("de-DE")[Quantity.ENERGY_CONSUMPTION],
        )
    }

    // ---- The invariant that keeps `get` total ----

    /**
     * Every quantity must have an entry, or a gauge somewhere asks for a unit that is not there
     * and dies at the presentation edge — the worst possible place.
     */
    @Test
    fun `every locale default covers every quantity`() {
        for (tag in listOf("en-US", "en-GB", "en-CA", "ko-KR", "de-DE", "xx-ZZ", "")) {
            val prefs = UnitPreferences.defaultsFor(tag)
            for (quantity in Quantity.entries) {
                assertEquals(quantity, prefs[quantity].quantity, "$tag / $quantity")
            }
        }
    }

    @Test
    fun `a preference map that misses a quantity is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            UnitPreferences(mapOf(Quantity.SPEED to UnitId.MPH))
        }
    }

    @Test
    fun `a preference map that files a unit under the wrong quantity is rejected`() {
        val broken = UnitPreferences.METRIC.byQuantity + (Quantity.SPEED to UnitId.CELSIUS)
        assertFailsWith<IllegalArgumentException> { UnitPreferences(broken) }
    }

    @Test
    fun `with replaces exactly one quantity`() {
        val prefs = UnitPreferences.METRIC.with(Quantity.PRESSURE, UnitId.PSI)
        assertEquals(UnitId.PSI, prefs[Quantity.PRESSURE])
        assertEquals(UnitId.KMH, prefs[Quantity.SPEED])
    }

    // ---- Persistence ----

    @Test
    fun `encode and decode round-trip`() {
        val gb = UnitPreferences.defaultsFor("en-GB")
        assertEquals(gb, UnitPreferences.decode(gb.encode()))
    }

    /**
     * A preferences file from a newer build — or a renamed enum — must not brick the app.
     * Unreadable entries fall back to the metric default, exactly as `speed_unit` already does.
     */
    @Test
    fun `decode tolerates junk and missing entries`() {
        assertEquals(UnitPreferences.METRIC, UnitPreferences.decode(null))
        assertEquals(UnitPreferences.METRIC, UnitPreferences.decode(""))
        assertEquals(UnitPreferences.METRIC, UnitPreferences.decode("garbage"))

        val partial = UnitPreferences.decode("SPEED=MPH,PRESSURE=FURLONGS,NONSENSE=X")
        assertEquals(UnitId.MPH, partial[Quantity.SPEED])
        assertEquals(UnitId.BAR, partial[Quantity.PRESSURE])
    }

    /** A stored unit filed under the wrong quantity is junk, not a crash. */
    @Test
    fun `decode rejects a unit that does not belong to its quantity`() {
        assertEquals(UnitId.KMH, UnitPreferences.decode("SPEED=CELSIUS")[Quantity.SPEED])
    }

    @Test
    fun `every unit has a distinct label key`() {
        val keys = UnitId.entries.map { it.labelKey }
        assertEquals(UnitId.entries.size, keys.toSet().size, "label keys must be unique")
    }
}
