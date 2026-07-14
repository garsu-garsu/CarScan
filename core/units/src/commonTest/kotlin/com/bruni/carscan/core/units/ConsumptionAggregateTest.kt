package com.bruni.carscan.core.units

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Trap 3, and this file is documentation as much as verification.
 *
 * Consumption is a ratio, so **you cannot average the displayed number.** The only correct
 * aggregate is total fuel over total distance. There is deliberately no API in this module that
 * accepts a `List<Double>` of consumption readings, because such an API cannot be used correctly
 * — it has already thrown away the distances that the weights depend on.
 */
class ConsumptionAggregateTest {

    private val convert = DefaultUnitConverter

    // Two legs of one trip. The distances are NOT equal, which is the entire point.
    private val cityLeg = ConsumptionAggregate(fuelLitres = 10.0, distanceKm = 100.0)   // 10 L/100km
    private val motorwayLeg = ConsumptionAggregate(fuelLitres = 15.0, distanceKm = 300.0) // 5 L/100km

    @Test
    fun `each leg on its own reads as expected`() {
        assertEquals(10.0, cityLeg.asL100km(), 1e-9)
        assertEquals(5.0, motorwayLeg.asL100km(), 1e-9)
    }

    /**
     * The naive average of the two *displayed* figures is 7.5 L/100km. The truth is 25 L over
     * 400 km = 6.25 L/100km. The naive answer is 20% too thirsty, and it is wrong for exactly
     * one reason: the legs were not the same length.
     */
    @Test
    fun `averaging the displayed L per 100km is wrong, the aggregate is right`() {
        val naive = (cityLeg.asL100km() + motorwayLeg.asL100km()) / 2.0
        val truth = (cityLeg + motorwayLeg).asL100km()

        assertEquals(7.5, naive, 1e-9)
        assertEquals(6.25, truth, 1e-9)
        assertTrue(naive > truth, "the naive mean overstates consumption here")
    }

    /**
     * And it is just as wrong in MPG, where the error runs the other way round — the naive mean
     * *understates* economy. Same physical trip, same bug, opposite sign: which is precisely why
     * you cannot eyeball your way out of this one.
     */
    @Test
    fun `averaging displayed MPG is wrong too, and errs in the other direction`() {
        val cityMpg = convert.convert(cityLeg.asL100km(), UnitId.L_PER_100KM, UnitId.MPG_US)
        val motorwayMpg = convert.convert(motorwayLeg.asL100km(), UnitId.L_PER_100KM, UnitId.MPG_US)

        val naive = (cityMpg + motorwayMpg) / 2.0
        val truth = convert.convert(
            (cityLeg + motorwayLeg).asL100km(),
            UnitId.L_PER_100KM,
            UnitId.MPG_US,
        )

        assertEquals(23.52, cityMpg, 0.01)
        assertEquals(47.04, motorwayMpg, 0.01)
        assertEquals(35.28, naive, 0.01)
        assertEquals(37.63, truth, 0.01)
        assertTrue(naive < truth, "the naive mean understates economy here")
    }

    /** Equal distances are the one case where the naive mean happens to be right. */
    @Test
    fun `the naive mean is only correct when the distances are equal`() {
        val a = ConsumptionAggregate(fuelLitres = 10.0, distanceKm = 100.0)
        val b = ConsumptionAggregate(fuelLitres = 5.0, distanceKm = 100.0)

        val naive = (a.asL100km() + b.asL100km()) / 2.0
        assertEquals(naive, (a + b).asL100km(), 1e-9)
    }

    /** Summing legs is associative, so a range query is just a fold over its trips. */
    @Test
    fun `aggregates sum`() {
        val legs = listOf(cityLeg, motorwayLeg, ConsumptionAggregate(5.0, 100.0))
        val total = legs.reduce(ConsumptionAggregate::plus)

        assertEquals(30.0, total.fuelLitres, 1e-9)
        assertEquals(500.0, total.distanceKm, 1e-9)
        assertEquals(6.0, total.asL100km(), 1e-9)
    }

    /** Trips store `fuel_ml` and `distance_m`; this is the only bridge they need. */
    @Test
    fun `an aggregate can be built from the integer millilitres and metres a trip stores`() {
        val trip = ConsumptionAggregate.ofRaw(fuelMl = 25_000, distanceMetres = 400_000)

        assertEquals(25.0, trip.fuelLitres, 1e-9)
        assertEquals(400.0, trip.distanceKm, 1e-9)
        assertEquals(6.25, trip.asL100km(), 1e-9)
    }

    /**
     * A trip that has not moved yet has no consumption — not a division by zero, and not a
     * fabricated 0.0 either, which would drag a running average down. NaN is the honest answer
     * and the UI renders it as a dash.
     */
    @Test
    fun `a zero-distance aggregate is NaN, not zero and not a crash`() {
        assertTrue(ConsumptionAggregate(fuelLitres = 0.5, distanceKm = 0.0).asL100km().isNaN())
        assertTrue(ConsumptionAggregate.EMPTY.asL100km().isNaN())
    }
}
