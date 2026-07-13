package com.bruni.carscan.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OBDb's canonical cross-vehicle metric. A Kia EV6 and a Ford F-150 report state
 * of charge through completely different commands and signal ids, but both tag
 * the signal `stateOfCharge`, so the dashboard can bind to the metric and stay
 * vehicle-agnostic.
 *
 * These are the 34 values in the OBDb schema, exactly — a mirror of
 * `suggestedMetricEnum` in
 * https://raw.githubusercontent.com/OBDb/.schemas/main/signals.json
 * Do not add to this enum: an unknown value must fail parsing loudly rather than
 * be invented here.
 *
 * Note what is NOT here: engine RPM, boost, intake temperature, and most of the
 * SAE J1979 signals. OBDb only standardizes metrics whose *acquisition* differs
 * across vehicles. That is why a dashboard tile is keyed on [MetricKey], not on
 * this enum directly.
 */
@Serializable
enum class SuggestedMetric {
    @SerialName("commandedLambda") COMMANDED_LAMBDA,
    @SerialName("cvtDeterioration") CVT_DETERIORATION,
    @SerialName("distanceSinceDTCsCleared") DISTANCE_SINCE_DTCS_CLEARED,
    @SerialName("electricRange") ELECTRIC_RANGE,
    @SerialName("engineCoolantTemperature") ENGINE_COOLANT_TEMPERATURE,
    @SerialName("engineLoad") ENGINE_LOAD,
    @SerialName("engineOilTemperature") ENGINE_OIL_TEMPERATURE,
    @SerialName("frontLeftTirePressure") FRONT_LEFT_TIRE_PRESSURE,
    @SerialName("frontLeftTireTemperature") FRONT_LEFT_TIRE_TEMPERATURE,
    @SerialName("frontRightTirePressure") FRONT_RIGHT_TIRE_PRESSURE,
    @SerialName("frontRightTireTemperature") FRONT_RIGHT_TIRE_TEMPERATURE,
    @SerialName("fuelRange") FUEL_RANGE,
    @SerialName("fuelRate") FUEL_RATE,
    @SerialName("fuelTankLevel") FUEL_TANK_LEVEL,
    @SerialName("isCharging") IS_CHARGING,
    @SerialName("massAirFlow") MASS_AIR_FLOW,
    @SerialName("o2Lambda") O2_LAMBDA,
    @SerialName("odometer") ODOMETER,
    @SerialName("pluggedIn") PLUGGED_IN,
    @SerialName("rearLeftTirePressure") REAR_LEFT_TIRE_PRESSURE,
    @SerialName("rearLeftTireTemperature") REAR_LEFT_TIRE_TEMPERATURE,
    @SerialName("rearRightTirePressure") REAR_RIGHT_TIRE_PRESSURE,
    @SerialName("rearRightTireTemperature") REAR_RIGHT_TIRE_TEMPERATURE,
    @SerialName("shortTermFuelTrim") SHORT_TERM_FUEL_TRIM,
    @SerialName("speed") SPEED,
    @SerialName("starterBatteryVoltage") STARTER_BATTERY_VOLTAGE,
    @SerialName("stateOfCharge") STATE_OF_CHARGE,
    @SerialName("stateOfHealth") STATE_OF_HEALTH,
    @SerialName("throttlePosition") THROTTLE_POSITION,
    @SerialName("tractionBatteryCapacity") TRACTION_BATTERY_CAPACITY,
    @SerialName("tractionBatteryCurrent") TRACTION_BATTERY_CURRENT,
    @SerialName("tractionBatteryEfficiency") TRACTION_BATTERY_EFFICIENCY,
    @SerialName("tractionBatteryVoltage") TRACTION_BATTERY_VOLTAGE,
    @SerialName("transmissionFluidTemperature") TRANSMISSION_FLUID_TEMPERATURE,
}

/** The only two grouped metrics OBDb standardizes (per-cell battery arrays). */
@Serializable
enum class SuggestedMetricGroup {
    @SerialName("batteryModulesStateOfCharge") BATTERY_MODULES_STATE_OF_CHARGE,
    @SerialName("batteryModulesVoltage") BATTERY_MODULES_VOLTAGE,
}

/**
 * What a dashboard tile, a chart series, and a stored sample are keyed on.
 *
 * A [SuggestedMetric] alone cannot address every signal: most SAE J1979 signals —
 * engine RPM among them — carry no metric at all. A signal id alone is no better,
 * because it is vehicle-specific (`EV6_HVBAT_SOC` means nothing on a Ford). So we
 * address by metric when OBDb gives us one, and fall back to the signal id when it
 * does not.
 */
@Serializable
sealed interface MetricKey {
    @Serializable
    data class Metric(val metric: SuggestedMetric) : MetricKey

    @Serializable
    data class Signal(val signalId: String) : MetricKey
}
