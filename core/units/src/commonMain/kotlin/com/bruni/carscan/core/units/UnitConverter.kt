package com.bruni.carscan.core.units

/**
 * Converts a value between two units of the same [Quantity].
 *
 * The interface has two functions rather than one because **a value and a difference do not
 * convert the same way.** Temperature is affine: 10 °C is 50 °F, but a *rise* of 10 °C is a rise
 * of 18 °F. Handing a ΔT to [convert] is a real bug that ships, so the two cases are different
 * functions and the compiler makes you say which one you meant.
 *
 * Both functions return a bare [Double], which is **not a displayable value.** Calling
 * `toString()` on it hard-codes a `'.'` decimal separator, and six of the eight shipping locales
 * write `13,8`. To put a converted number on screen, go through [UnitReadout], which converts and
 * formats in one step precisely so the two cannot drift apart.
 */
interface UnitConverter {

    /**
     * Converts an absolute reading — a coolant temperature, a road speed, a boost pressure.
     *
     * @throws IllegalArgumentException if [from] and [to] are different quantities. There is no
     *   sensible number to return, and returning one anyway is how a dashboard ends up showing a
     *   confident lie.
     */
    fun convert(value: Double, from: UnitId, to: UnitId): Double

    /**
     * Converts a **difference** between two readings — a temperature rise, a speed increase.
     *
     * @throws IllegalArgumentException if either unit is an inverse ([UnitId.isInverse]): the
     *   difference of two mpg readings is not an mpg, and no conversion of it means anything.
     */
    fun convertDelta(value: Double, from: UnitId, to: UnitId): Double
}

/** The single implementation. Stateless, so a single instance serves the whole app. */
object DefaultUnitConverter : UnitConverter {

    override fun convert(value: Double, from: UnitId, to: UnitId): Double {
        requireSameQuantity(from, to)
        if (from == to) return value
        return fromBase(toBase(value, from), to)
    }

    override fun convertDelta(value: Double, from: UnitId, to: UnitId): Double {
        requireSameQuantity(from, to)
        require(!from.isInverse && !to.isInverse) {
            "a difference in $from/$to is not a meaningful quantity: these units are inverses of " +
                "what they measure, so the difference of two readings does not convert"
        }
        if (from == to) return value

        // Every non-inverse unit here is affine in its base: base = a*v + b. A difference cancels
        // the offset b — which is the entire distinction between this function and convert() —
        // and subtracting the image of zero is how that cancellation is expressed without
        // special-casing temperature.
        val deltaInBase = toBase(value, from) - toBase(0.0, from)
        return fromBase(deltaInBase, to) - fromBase(0.0, to)
    }

    private fun requireSameQuantity(from: UnitId, to: UnitId) {
        require(from.quantity == to.quantity) {
            "cannot convert $from (${from.quantity}) to $to (${to.quantity})"
        }
    }
}

// Exact, by definition, in every case below. These are the numbers that a hand-rolled converter
// rounds to four decimals and is then quietly 0.1% wrong forever.
private const val KM_PER_MILE = 1.609344
private const val KPA_PER_PSI = 6.894757293168361
private const val LITRES_PER_US_GALLON = 3.785411784
private const val LITRES_PER_UK_GALLON = 4.54609
private const val KW_PER_HP = 0.7456998715822702      // mechanical horsepower
private const val KW_PER_PS = 0.73549875              // metric horsepower (Pferdestärke)
private const val NM_PER_LB_FT = 1.3558179483314004

private const val MILES_PER_100KM = 100.0 / KM_PER_MILE          // 62.1371…

/**
 * `mpg = C / (L/100km)`. C is (miles in 100 km) × (litres in a gallon).
 *
 * **The US and imperial gallons are not the same gallon**, so these two constants differ by ~20%
 * — and so do the mpg figures they produce for one physical consumption. Showing a British
 * driver the US number is the single most reliable way to earn a one-star review from the UK.
 */
private const val MPG_US_NUMERATOR = MILES_PER_100KM * LITRES_PER_US_GALLON   // 235.2145…
private const val MPG_UK_NUMERATOR = MILES_PER_100KM * LITRES_PER_UK_GALLON   // 282.4809…

/** Converts a value into its quantity's base unit: km/h, km, kPa, °C, L, L/100km, kWh/100km, kW, N·m. */
private fun toBase(value: Double, unit: UnitId): Double = when (unit) {
    UnitId.KMH -> value
    UnitId.MPH -> value * KM_PER_MILE

    UnitId.KM -> value
    UnitId.MILES -> value * KM_PER_MILE

    UnitId.KPA -> value
    UnitId.BAR -> value * 100.0
    UnitId.PSI -> value * KPA_PER_PSI

    UnitId.CELSIUS -> value
    UnitId.FAHRENHEIT -> (value - 32.0) * 5.0 / 9.0

    UnitId.LITRE -> value
    UnitId.US_GALLON -> value * LITRES_PER_US_GALLON
    UnitId.UK_GALLON -> value * LITRES_PER_UK_GALLON

    UnitId.L_PER_100KM -> value
    UnitId.KM_PER_L -> 100.0 / value
    UnitId.MPG_US -> MPG_US_NUMERATOR / value
    UnitId.MPG_UK -> MPG_UK_NUMERATOR / value

    UnitId.KWH_PER_100KM -> value
    UnitId.MI_PER_KWH -> MILES_PER_100KM / value

    UnitId.KW -> value
    UnitId.HP -> value * KW_PER_HP
    UnitId.PS -> value * KW_PER_PS

    UnitId.NM -> value
    UnitId.LB_FT -> value * NM_PER_LB_FT
}

/** The exact inverse of [toBase]. */
private fun fromBase(base: Double, unit: UnitId): Double = when (unit) {
    UnitId.KMH -> base
    UnitId.MPH -> base / KM_PER_MILE

    UnitId.KM -> base
    UnitId.MILES -> base / KM_PER_MILE

    UnitId.KPA -> base
    UnitId.BAR -> base / 100.0
    UnitId.PSI -> base / KPA_PER_PSI

    UnitId.CELSIUS -> base
    UnitId.FAHRENHEIT -> base * 9.0 / 5.0 + 32.0

    UnitId.LITRE -> base
    UnitId.US_GALLON -> base / LITRES_PER_US_GALLON
    UnitId.UK_GALLON -> base / LITRES_PER_UK_GALLON

    UnitId.L_PER_100KM -> base
    UnitId.KM_PER_L -> 100.0 / base
    UnitId.MPG_US -> MPG_US_NUMERATOR / base
    UnitId.MPG_UK -> MPG_UK_NUMERATOR / base

    UnitId.KWH_PER_100KM -> base
    UnitId.MI_PER_KWH -> MILES_PER_100KM / base

    UnitId.KW -> base
    UnitId.HP -> base / KW_PER_HP
    UnitId.PS -> base / KW_PER_PS

    UnitId.NM -> base
    UnitId.LB_FT -> base / NM_PER_LB_FT
}
