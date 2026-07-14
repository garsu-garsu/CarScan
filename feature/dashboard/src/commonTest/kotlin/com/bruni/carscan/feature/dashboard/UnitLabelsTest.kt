package com.bruni.carscan.feature.dashboard

import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.unit_celsius
import com.bruni.carscan.core.designsystem.generated.resources.unit_degrees
import com.bruni.carscan.core.designsystem.generated.resources.unit_fahrenheit
import com.bruni.carscan.core.designsystem.generated.resources.unit_mph
import com.bruni.carscan.core.designsystem.generated.resources.unit_rpm
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.units.UnitId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class UnitLabelsTest {

    /**
     * **`degrees` is an ANGLE.** Ignition timing advance, steering angle.
     *
     * `°` and `°C` are one character apart, and this is the single quietest bug available in this
     * codebase: the *value* is never converted (there is no `UnitId` for an angle, so the mapper
     * correctly leaves it alone), so the number on the gauge is **right**. Only the word beside it
     * would be wrong, and "14 °C" under a needle looks like a perfectly ordinary temperature.
     * Nothing about the screen looks broken.
     *
     * `:core:units`' `ObdUnit.asIsLabelKey` returns `"unit_celsius"` for `DEGREES` today — in a
     * branch whose own comment says it must not — and because that helper returns a plain `String`
     * key rather than a generated accessor, the compiler cannot catch it. This module therefore
     * resolves its own labels, and this test is what holds it.
     */
    @Test
    fun `degrees is an angle, not a temperature`() {
        unitLabelResource(displayUnit = null, nativeUnit = ObdUnit.DEGREES) shouldBe
            Res.string.unit_degrees

        unitLabelResource(null, ObdUnit.DEGREES) shouldNotBe Res.string.unit_celsius
        unitLabelResource(null, ObdUnit.DEGREES) shouldNotBe Res.string.unit_fahrenheit
    }

    /** A real temperature still is one. The point is the distinction, not avoiding celsius. */
    @Test
    fun `celsius is a temperature`() {
        unitLabelResource(null, ObdUnit.CELSIUS) shouldBe Res.string.unit_celsius
    }

    /** The display unit wins: a converted value must not be labelled with what it was converted from. */
    @Test
    fun `a converted value is labelled with the unit it was converted to`() {
        unitLabelResource(UnitId.MPH, ObdUnit.KILOMETERS_PER_HOUR) shouldBe Res.string.unit_mph
    }

    /** Units the app offers no choice in are still named — a tachometer says rpm. */
    @Test
    fun `an unconvertible unit is still named`() {
        unitLabelResource(null, ObdUnit.RPM) shouldBe Res.string.unit_rpm
    }

    /** Not a physical quantity: no symbol at all, rather than its enum name under a gauge. */
    @Test
    fun `a value with no unit gets no symbol`() {
        unitLabelResource(null, ObdUnit.SCALAR) shouldBe null
        unitLabelResource(null, null) shouldBe null
    }
}
