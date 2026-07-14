package com.bruni.carscan.core.units

import com.bruni.carscan.core.model.ObdUnit

/**
 * The [UnitId] this OBDb-native unit *is*, or `null` if the app has no display unit for it.
 *
 * OBDb declares 81 units. Most of them need no conversion (`percent`, `rpm`, `volts`), several are
 * not physical quantities at all (`hex`, `scalar`, `gravity`), and a few are quantities we simply
 * do not offer a choice in (`kelvin`, `metersPerSecond`). All of those return `null` and are shown
 * exactly as decoded.
 *
 * **`null` is the honest answer, not a gap to fill.** A made-up conversion is indistinguishable
 * from a real one once it is on a gauge.
 */
fun ObdUnit.toUnitId(): UnitId? = when (this) {
    ObdUnit.KILOMETERS_PER_HOUR -> UnitId.KMH
    ObdUnit.MILES_PER_HOUR -> UnitId.MPH

    ObdUnit.KILOMETERS -> UnitId.KM
    ObdUnit.MILES -> UnitId.MILES

    ObdUnit.KILOPASCAL -> UnitId.KPA
    ObdUnit.BARS -> UnitId.BAR
    ObdUnit.PSI -> UnitId.PSI

    ObdUnit.CELSIUS -> UnitId.CELSIUS
    ObdUnit.FAHRENHEIT -> UnitId.FAHRENHEIT

    ObdUnit.LITERS -> UnitId.LITRE
    // OBDb does not say *which* gallon. Read as the US gallon, because OBDb is a US-authored
    // database — but this is an assumption, and if it is wrong it is wrong by 20%. If real data
    // ever contradicts it, this line is the fix.
    ObdUnit.GALLONS -> UnitId.US_GALLON

    ObdUnit.KILOWATT_HOURS_PER_100_KILOMETERS -> UnitId.KWH_PER_100KM
    ObdUnit.MILES_PER_KILOWATT_HOUR -> UnitId.MI_PER_KWH

    ObdUnit.KILOWATTS -> UnitId.KW

    ObdUnit.NEWTON_METERS -> UnitId.NM
    ObdUnit.POUND_FOOT -> UnitId.LB_FT

    else -> null
}

/**
 * The unit a sample tagged [native] should be *drawn* in, given the user's [prefs] — or `null` if
 * it has no display unit and must be shown as decoded.
 *
 * Resolution is by **quantity, not identity**: a signal that OBDb reports natively in mph is still
 * drawn in km/h for a German user. The native unit only says what the number currently means.
 */
fun displayUnitFor(native: ObdUnit, prefs: UnitPreferences): UnitId? =
    native.toUnitId()?.let { prefs[it.quantity] }

/**
 * The label key for a unit we do **not** convert but can still name — rpm, volts, percent.
 *
 * Not converting is not the same as not naming: a tachometer still says *rpm* and a battery gauge
 * still says *V*. Without this, every feature would hand-roll its own `ObdUnit`-to-label table.
 *
 * `null` in two different cases, and both are correct:
 * - the unit *is* convertible ([toUnitId] names it), so its label depends on what the user chose
 *   to see it in — ask [displayUnitFor] and read [UnitId.labelKey];
 * - the value is not a physical quantity (`hex`, `scalar`, `gravity`, `ascii`), so there is no
 *   unit to put beside it.
 */
val ObdUnit.asIsLabelKey: String?
    get() = if (toUnitId() != null) {
        null
    } else {
        when (this) {
            ObdUnit.RPM -> "unit_rpm"
            ObdUnit.VOLTS -> "unit_volts"
            ObdUnit.PERCENT -> "unit_percent"
            ObdUnit.SECONDS -> "unit_seconds"
            ObdUnit.GRAMS_PER_SECOND -> "unit_grams_per_second"
            ObdUnit.LITERS_PER_HOUR -> "unit_liters_per_hour"

            // An **angle** — ignition timing advance, steering angle. A bare `°`, and emphatically
            // not `°C`: the two are one keystroke apart here and both look plausible on a gauge.
            ObdUnit.DEGREES -> "unit_degrees"

            ObdUnit.AMPS -> "unit_amps"
            ObdUnit.MILLIAMPS -> "unit_milliamps"
            ObdUnit.WATTS -> "unit_watts"
            ObdUnit.KILOWATT_HOURS -> "unit_kilowatt_hours"
            ObdUnit.AMPERE_HOURS -> "unit_ampere_hours"
            ObdUnit.HERTZ -> "unit_hertz"
            ObdUnit.KILOOHMS -> "unit_kiloohms"
            ObdUnit.MILLIMETERS -> "unit_millimeters"
            ObdUnit.MINUTES -> "unit_minutes"
            ObdUnit.HOURS -> "unit_hours"
            ObdUnit.MILLISECONDS -> "unit_milliseconds"
            ObdUnit.KILOGRAMS_PER_HOUR -> "unit_kilograms_per_hour"
            ObdUnit.METERS_PER_SECOND_SQUARED -> "unit_meters_per_second_squared"
            ObdUnit.MILLIGRAMS_PER_STROKE -> "unit_milligrams_per_stroke"

            else -> null
        }
    }
