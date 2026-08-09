package com.bruni.carscan.core.units

/**
 * Fuel used over distance covered — **the only correct way to aggregate consumption.**
 *
 * L/100km is a ratio, and mpg is its inverse, so *you cannot average the displayed number.* A leg
 * at 10 L/100km followed by a leg at 5 L/100km does not average to 7.5 unless the two legs were
 * exactly the same length — and they never are. The mean has to be weighted by distance, which
 * means the distances must still be there when you take it.
 *
 * That is why this module offers **no function anywhere that accepts a list of consumption
 * readings.** Such a function cannot be called correctly: by the time you hold the readings, the
 * weights are gone. Every average over any range is instead `SUM(fuel) / SUM(distance)`, which is
 * exactly this type — and trips already store `fuel_ml` and `distance_m`, so nothing is lost.
 *
 * Convert at *display* time ([DefaultUnitConverter.convert] from [UnitId.L_PER_100KM]), never before.
 */
data class ConsumptionAggregate(val fuelLitres: Double, val distanceKm: Double) {

    /**
     * The one correct path to a consumption figure.
     *
     * Returns `NaN` when nothing has been driven yet. Not `0.0` — a car that has not moved has not
     * achieved 0 L/100km, and feeding that zero into a running total would understate every
     * average that follows it. The UI renders NaN as a dash.
     *
     * **No fuel is the same kind of unknown**, and it is the common one: `fuel_ml` is integrated
     * from the fuel-rate signal, which plenty of vehicles simply do not expose — and an EV never
     * will, its economy being [Quantity.ENERGY_CONSUMPTION], a different quantity entirely. Such a
     * trip has a real distance and zero litres, and dividing gives `0.0 L/100km`, which is not a
     * dash and not obviously wrong: it is a *plausible* number, and in km/L or mpg — where the
     * conversion is `100 / value` — it becomes `∞`. Zero fuel over a real distance means the car
     * never reported, never that it drank nothing.
     */
    fun asL100km(): Double =
        if (distanceKm <= 0.0 || fuelLitres <= 0.0) Double.NaN else fuelLitres / distanceKm * 100.0

    /** Aggregates are additive, so a range query over trips is a fold. */
    operator fun plus(other: ConsumptionAggregate): ConsumptionAggregate =
        ConsumptionAggregate(fuelLitres + other.fuelLitres, distanceKm + other.distanceKm)

    companion object {
        val EMPTY = ConsumptionAggregate(fuelLitres = 0.0, distanceKm = 0.0)

        /** From the millilitres and metres a trip row actually stores (`REAL`, not integers). */
        fun ofRaw(fuelMl: Double, distanceMetres: Double): ConsumptionAggregate =
            ConsumptionAggregate(
                fuelLitres = fuelMl / 1_000.0,
                distanceKm = distanceMetres / 1_000.0,
            )
    }
}
