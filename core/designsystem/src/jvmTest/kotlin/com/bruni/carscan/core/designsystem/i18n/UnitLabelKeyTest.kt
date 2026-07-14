package com.bruni.carscan.core.designsystem.i18n

import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.units.asIsLabelKey
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An angle is not a temperature.
 *
 * `StringResourceParityTest` already proves every label key `:core:units` can emit is a string we
 * ship. This is the failure that survives that: **every key exists, and the wrong one is chosen.**
 *
 * OBDb's `degrees` is ignition timing advance and steering angle — a bare `°`. It was mapped to
 * `unit_celsius`, directly beneath a comment explaining that it must not be, so a timing-advance
 * gauge would have read "14 °C". The value correct, the unit beside it a lie, and nothing about the
 * reading looking wrong. That is the failure mode this project keeps producing, and no amount of
 * key-existence checking can see it — `unit_celsius` is a perfectly real key.
 */
class UnitLabelKeyTest {

    @Test
    fun `degrees is an angle and never a temperature`() {
        assertEquals(
            "unit_degrees",
            ObdUnit.DEGREES.asIsLabelKey,
            "ObdUnit.DEGREES is an ANGLE — ignition timing, steering — not a temperature. " +
                "Labelling it as celsius puts a plausible, invisible lie on a gauge.",
        )
    }
}
