package com.bruni.carscan.feature.dashboard

import androidx.compose.runtime.Composable
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.gauge_battery_voltage
import com.bruni.carscan.core.designsystem.generated.resources.gauge_coolant_temp
import com.bruni.carscan.core.designsystem.generated.resources.gauge_engine_load
import com.bruni.carscan.core.designsystem.generated.resources.gauge_engine_speed
import com.bruni.carscan.core.designsystem.generated.resources.gauge_fuel_level
import com.bruni.carscan.core.designsystem.generated.resources.gauge_mass_air_flow
import com.bruni.carscan.core.designsystem.generated.resources.gauge_state_of_charge
import com.bruni.carscan.core.designsystem.generated.resources.gauge_throttle_position
import com.bruni.carscan.core.designsystem.generated.resources.gauge_vehicle_speed
import com.bruni.carscan.core.designsystem.generated.resources.unit_bar
import com.bruni.carscan.core.designsystem.generated.resources.unit_celsius
import com.bruni.carscan.core.designsystem.generated.resources.unit_degrees
import com.bruni.carscan.core.designsystem.generated.resources.unit_fahrenheit
import com.bruni.carscan.core.designsystem.generated.resources.unit_grams_per_second
import com.bruni.carscan.core.designsystem.generated.resources.unit_hp
import com.bruni.carscan.core.designsystem.generated.resources.unit_km
import com.bruni.carscan.core.designsystem.generated.resources.unit_km_per_l
import com.bruni.carscan.core.designsystem.generated.resources.unit_kmh
import com.bruni.carscan.core.designsystem.generated.resources.unit_kpa
import com.bruni.carscan.core.designsystem.generated.resources.unit_kw
import com.bruni.carscan.core.designsystem.generated.resources.unit_kwh_per_100km
import com.bruni.carscan.core.designsystem.generated.resources.unit_l_per_100km
import com.bruni.carscan.core.designsystem.generated.resources.unit_lb_ft
import com.bruni.carscan.core.designsystem.generated.resources.unit_liters_per_hour
import com.bruni.carscan.core.designsystem.generated.resources.unit_litre
import com.bruni.carscan.core.designsystem.generated.resources.unit_mi_per_kwh
import com.bruni.carscan.core.designsystem.generated.resources.unit_miles
import com.bruni.carscan.core.designsystem.generated.resources.unit_mpg_uk
import com.bruni.carscan.core.designsystem.generated.resources.unit_mpg_us
import com.bruni.carscan.core.designsystem.generated.resources.unit_mph
import com.bruni.carscan.core.designsystem.generated.resources.unit_nm
import com.bruni.carscan.core.designsystem.generated.resources.unit_percent
import com.bruni.carscan.core.designsystem.generated.resources.unit_ps
import com.bruni.carscan.core.designsystem.generated.resources.unit_psi
import com.bruni.carscan.core.designsystem.generated.resources.unit_rpm
import com.bruni.carscan.core.designsystem.generated.resources.unit_seconds
import com.bruni.carscan.core.designsystem.generated.resources.unit_us_gallon
import com.bruni.carscan.core.designsystem.generated.resources.unit_uk_gallon
import com.bruni.carscan.core.designsystem.generated.resources.unit_volts
import com.bruni.carscan.core.model.MetricKey
import com.bruni.carscan.core.model.ObdUnit
import com.bruni.carscan.core.model.SuggestedMetric
import com.bruni.carscan.core.units.UnitId
import com.bruni.carscan.core.units.toUnitId
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Where a tile's words come from — and the reason [TileState] carries units rather than strings.
 *
 * The mapper decides which *unit* a tile is in; only a composable can turn that decision into a
 * word, because a word depends on the locale. Doing it here also means the eight locales are
 * translated once, in `:core:designsystem`, rather than eight times across the features.
 */

/** The symbol under the number: the display unit's, or the native one when no choice is offered. */
@Composable
fun unitLabelOf(displayUnit: UnitId?, nativeUnit: ObdUnit?): String =
    unitLabelResource(displayUnit, nativeUnit)?.let { stringResource(it) }.orEmpty()

/**
 * Which string names this tile's unit — resolved without a composition, so it can be *tested*.
 *
 * Keeping this out of the `@Composable` is not tidiness. The one bug this mapping can have is
 * naming a unit as something it is not, and that bug is invisible on screen: an angle labelled
 * `°C` looks exactly like a temperature. A composable cannot be asserted on from `commonTest`
 * (Compose UI tests die in `androidHostTest` on a null `Build.FINGERPRINT`), so the decision is
 * made here, where a test can hold it to account, and the composable only spells the answer.
 */
fun unitLabelResource(displayUnit: UnitId?, nativeUnit: ObdUnit?): StringResource? =
    displayUnit?.labelResource() ?: nativeUnit?.labelResource()

/**
 * A tile's title.
 *
 * OBDb signal names are English and vehicle-specific ("Vehicle speed", "EV6_HVBAT_SOC"). Where the
 * signal carries a canonical metric there is a translated name for it, so use that; otherwise fall
 * back to what OBDb called it, which is at least accurate.
 */
@Composable
fun tileLabelOf(key: MetricKey, fallback: String): String =
    key.labelResource()?.let { stringResource(it) } ?: fallback

private fun UnitId.labelResource(): StringResource = when (this) {
    UnitId.KMH -> Res.string.unit_kmh
    UnitId.MPH -> Res.string.unit_mph
    UnitId.KM -> Res.string.unit_km
    UnitId.MILES -> Res.string.unit_miles
    UnitId.KPA -> Res.string.unit_kpa
    UnitId.BAR -> Res.string.unit_bar
    UnitId.PSI -> Res.string.unit_psi
    UnitId.CELSIUS -> Res.string.unit_celsius
    UnitId.FAHRENHEIT -> Res.string.unit_fahrenheit
    UnitId.LITRE -> Res.string.unit_litre
    UnitId.US_GALLON -> Res.string.unit_us_gallon
    UnitId.UK_GALLON -> Res.string.unit_uk_gallon
    UnitId.L_PER_100KM -> Res.string.unit_l_per_100km
    UnitId.KM_PER_L -> Res.string.unit_km_per_l
    UnitId.MPG_US -> Res.string.unit_mpg_us
    UnitId.MPG_UK -> Res.string.unit_mpg_uk
    UnitId.KWH_PER_100KM -> Res.string.unit_kwh_per_100km
    UnitId.MI_PER_KWH -> Res.string.unit_mi_per_kwh
    UnitId.KW -> Res.string.unit_kw
    UnitId.HP -> Res.string.unit_hp
    UnitId.PS -> Res.string.unit_ps
    UnitId.NM -> Res.string.unit_nm
    UnitId.LB_FT -> Res.string.unit_lb_ft
}

/** A unit's symbol: the one it converts to if it converts, else its own. */
private fun ObdUnit.labelResource(): StringResource? = toUnitId()?.labelResource() ?: asIsLabel()

/**
 * The symbols for units the app offers no *choice* in — rpm, percent, volts, an angle.
 *
 * These have no [UnitId] by construction: there is nothing to convert them to. Anything not listed
 * gets no symbol at all rather than its enum name, because "GRAMS_PER_LITER" under a gauge is
 * worse than nothing.
 */
private fun ObdUnit.asIsLabel(): StringResource? = when (this) {
    ObdUnit.RPM -> Res.string.unit_rpm
    ObdUnit.PERCENT -> Res.string.unit_percent
    ObdUnit.VOLTS -> Res.string.unit_volts
    ObdUnit.LITERS_PER_HOUR -> Res.string.unit_liters_per_hour
    ObdUnit.GRAMS_PER_SECOND -> Res.string.unit_grams_per_second
    ObdUnit.SECONDS -> Res.string.unit_seconds

    // An **ANGLE** — ignition timing advance, steering angle — and emphatically not a temperature.
    // `°` and `°C` are one character apart and both look entirely plausible under a needle: 14° of
    // advance rendered as "14 °C" is wrong in a way no one will ever notice. `:core:units`'
    // `asIsLabelKey` gets this wrong today (it returns `unit_celsius`), which is exactly why this
    // module resolves its own labels through a compile-checked `when` rather than trusting a
    // String key that cannot fail the build.
    ObdUnit.DEGREES -> Res.string.unit_degrees

    else -> null
}

private fun MetricKey.labelResource(): StringResource? = when (this) {
    is MetricKey.Metric -> when (metric) {
        SuggestedMetric.SPEED -> Res.string.gauge_vehicle_speed
        SuggestedMetric.ENGINE_COOLANT_TEMPERATURE -> Res.string.gauge_coolant_temp
        SuggestedMetric.ENGINE_LOAD -> Res.string.gauge_engine_load
        SuggestedMetric.THROTTLE_POSITION -> Res.string.gauge_throttle_position
        SuggestedMetric.STARTER_BATTERY_VOLTAGE -> Res.string.gauge_battery_voltage
        SuggestedMetric.FUEL_TANK_LEVEL -> Res.string.gauge_fuel_level
        SuggestedMetric.MASS_AIR_FLOW -> Res.string.gauge_mass_air_flow
        SuggestedMetric.STATE_OF_CHARGE -> Res.string.gauge_state_of_charge
        else -> null
    }

    // OBDb gives engine RPM no `suggestedMetric`, so the app's most important gauge can only be
    // named through its signal id. That is the whole reason MetricKey has a Signal case.
    is MetricKey.Signal -> if (signalId == "RPM") Res.string.gauge_engine_speed else null
}
