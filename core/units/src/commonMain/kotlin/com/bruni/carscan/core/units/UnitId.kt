package com.bruni.carscan.core.units

/**
 * A physical quantity the app can display in more than one unit.
 *
 * Preferences are held per quantity — never as one metric/imperial switch. A British driver
 * reads **miles, litres and imperial MPG at the same time**, and no boolean can say that.
 *
 * [CONSUMPTION] is fuel economy and [ENERGY_CONSUMPTION] is electric economy. They are separate
 * quantities because litres and kilowatt-hours do not convert into one another without a fuel
 * energy density this layer does not have — and because a single preference would otherwise
 * render an EV's kWh/100km as imperial MPG for a British user.
 */
enum class Quantity {
    SPEED,
    DISTANCE,
    PRESSURE,
    TEMPERATURE,
    VOLUME,
    CONSUMPTION,
    ENERGY_CONSUMPTION,
    POWER,
    TORQUE,
}

/**
 * Every unit the app is willing to display a value in.
 *
 * [labelKey] is a stable symbol, not a translated string: `:core:units` deliberately has no
 * `composeResources` dependency, so the UI resolves the key against `:core:designsystem`'s
 * string resources. The keys are owned jointly with the design system and must not be renamed
 * on one side only.
 */
enum class UnitId(val quantity: Quantity, val labelKey: String) {
    KMH(Quantity.SPEED, "unit_kmh"),
    MPH(Quantity.SPEED, "unit_mph"),

    KM(Quantity.DISTANCE, "unit_km"),
    MILES(Quantity.DISTANCE, "unit_miles"),

    KPA(Quantity.PRESSURE, "unit_kpa"),
    BAR(Quantity.PRESSURE, "unit_bar"),
    PSI(Quantity.PRESSURE, "unit_psi"),

    CELSIUS(Quantity.TEMPERATURE, "unit_celsius"),
    FAHRENHEIT(Quantity.TEMPERATURE, "unit_fahrenheit"),

    LITRE(Quantity.VOLUME, "unit_litre"),
    US_GALLON(Quantity.VOLUME, "unit_us_gallon"),
    UK_GALLON(Quantity.VOLUME, "unit_uk_gallon"),

    L_PER_100KM(Quantity.CONSUMPTION, "unit_l_per_100km"),
    KM_PER_L(Quantity.CONSUMPTION, "unit_km_per_l"),
    MPG_US(Quantity.CONSUMPTION, "unit_mpg_us"),
    MPG_UK(Quantity.CONSUMPTION, "unit_mpg_uk"),

    KWH_PER_100KM(Quantity.ENERGY_CONSUMPTION, "unit_kwh_per_100km"),
    MI_PER_KWH(Quantity.ENERGY_CONSUMPTION, "unit_mi_per_kwh"),

    KW(Quantity.POWER, "unit_kw"),
    HP(Quantity.POWER, "unit_hp"),
    PS(Quantity.POWER, "unit_ps"),

    NM(Quantity.TORQUE, "unit_nm"),
    LB_FT(Quantity.TORQUE, "unit_lb_ft"),
}

/**
 * Units that get *bigger* as the thing they measure gets *smaller* — mpg and km/L rise as
 * consumption falls.
 *
 * Two consequences, and both are load-bearing:
 * - a **difference** in such a unit is meaningless, so [DefaultUnitConverter.convertDelta] refuses one;
 * - a **mean** of such values is not the mean consumption, which is why aggregation exists only
 *   as [ConsumptionAggregate] and never as a list of readings.
 */
val UnitId.isInverse: Boolean
    get() = this == UnitId.KM_PER_L || this == UnitId.MPG_US ||
        this == UnitId.MPG_UK || this == UnitId.MI_PER_KWH
